(ns yetibot-kroki.client-test
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [midje.sweet :refer [fact => throws]]
            [yetibot-kroki.client :as kroki]
            [yetibot.core.util.public-result :as public]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.webapp.routes.images :as images])
  (:import [java.io ByteArrayInputStream]))

(def png (byte-array (map unchecked-byte [137 80 78 71 13 10 26 10 0])))
(defn response [mime bytes]
  {:status 200 :headers {"content-type" mime}
   :body (ByteArrayInputStream. bytes)})
(def health {:status "pass" :version {:kroki {:number "0.36.0"}
                                     :lini "1.1.0" :goat "0.5.1"
                                     :diagramsnet "16" :dot "14"
                                     :future-engine "42"}})

(fact "engine discovery is live, sorted, cached, and stale is explicit"
  (let [reads (atom 0)]
    (with-redefs [kroki/catalog-cache (atom nil)
                  http/request (fn [opts]
                                 (swap! reads inc)
                                 (:url opts) => "https://kroki.io/health"
                                 (response "application/json"
                                           (.getBytes (json/generate-string health) "UTF-8")))]
      (let [data (:result/data (kroki/types!))]
        (mapv :type (:engines data)) => ["diagramsnet" "dot" "future-engine" "goat" "lini"]
        (:server-version data) => "0.36.0"
        (:stale data) => false
        (mapv :type (:result/public-data (public/projection (kroki/types!))))
        => ["diagramsnet" "dot" "future-engine" "goat" "lini"]
        (every? #(false? (:stale %))
                (:result/public-data (public/projection (kroki/types!)))) => true
        @reads => 1
        (swap! kroki/catalog-cache assoc :expires-at 0)
        (with-redefs [http/request (fn [_] (throw (Exception. "PRIVATE-ENDPOINT")))]
          (let [stale (:result/data (kroki/types!))]
            (:stale stale) => true
            (:engines stale) => (:engines data)
            (str/includes? (pr-str stale) "PRIVATE") => false))))))

(fact "malformed discovery JSON is an invalid response, not a transport failure"
  (with-redefs [kroki/catalog-cache (atom nil)
                http/request (fn [_] (response "application/json" (.getBytes "PRIVATE-invalid" "UTF-8")))]
    (let [result (kroki/types!)]
      (get-in result [:result/public-error :kind]) => "invalid-response"
      (str/includes? (pr-str result) "PRIVATE") => false)))

(fact "an exhausted response deadline closes the stream before reading"
  (let [closed? (atom false)
        stream (proxy [ByteArrayInputStream] [png]
                 (close [] (reset! closed? true)))]
    (#'kroki/bounded-bytes stream 100 0) => (throws clojure.lang.ExceptionInfo)
    @closed? => true
    (.available stream) => (alength png)))

(fact "rendering needs no discovery, POSTs literal Unicode/options and stores exact bytes"
  (let [sent (atom nil)]
    (with-redefs [images/image-store (atom {})
                  gemini/yetibot-base-url (constantly "https://bot.example")
                  http/request (fn [opts] (reset! sent opts) (response "image/png" png))]
      (let [source "digraph G { Café -> 雪; }"
            result (kroki/render! {:type "future-engine" :source source
                                   :options {"layout" "tala"}})
            data (:result/data result)]
        (:result/error result) => nil
        (:method @sent) => :post
        (:redirect-strategy @sent) => :none
        ((:retry-handler @sent) nil nil nil) => false
        (:url @sent) => "https://kroki.io/future-engine"
        (get-in @sent [:headers "Accept"]) => "image/png, image/svg+xml"
        (json/parse-string (:body @sent))
        => {"diagram_source" source "diagram_options" {"layout" "tala"}}
        (:format data) => "png"
        (:bytes data) => (alength png)
        (:result/public-data (public/projection result)) => data
        (boolean (re-find #"https://bot.example/generated-images/[a-f0-9-]+.png" (:url data))) => true
        (str/includes? (pr-str result) source) => false
        (seq @images/image-store) => seq))))

(fact "SVG-only engines negotiate without a hardcoded engine list"
  (with-redefs [images/image-store (atom {})
                gemini/yetibot-base-url (constantly "https://bot.example")
                http/request (fn [_] (response "image/svg+xml; charset=utf-8"
                                               (.getBytes "<svg xmlns='http://www.w3.org/2000/svg'/>" "UTF-8")))]
    (get-in (kroki/render! {:type "brand-new-engine" :source "diagram"})
            [:result/data :format]) => "svg"))

(fact "explicit output format is sent authoritatively"
  (with-redefs [images/image-store (atom {})
                gemini/yetibot-base-url (constantly "https://bot.example")
                http/request (fn [opts]
                               (:url opts) => "https://kroki.io/d2/pdf"
                               (response "application/pdf" (.getBytes "%PDF-1.4 test" "UTF-8")))]
    (get-in (kroki/render! {:type "d2" :source "x -> y" :format "pdf"})
            [:result/data :format]) => "pdf"))

(fact "invalid arguments are rejected before transport"
  (with-redefs [http/request (fn [_] (throw (AssertionError. "Unexpected IO")))]
    (doseq [request [{:type "../admin" :source "x"} {:type "x" :source ""}
                     {:type "x" :source "x" :format "html"}
                     {:type "x" :source "x" :options {"endpoint" {"url" "bad"}}}
                     {:type "x" :source (apply str (repeat 262145 "x"))}
                     {:type "x" :source (apply str (repeat 100000 "雪"))}
                     {:type "x" :source "x" :options {"layout" Double/POSITIVE_INFINITY}}]]
      (:result/error (kroki/render! request)) => string?)))

(fact "failures have safe rich diagnostics and never store invalid artifacts"
  (doseq [reply [{:status 400 :headers {} :body (ByteArrayInputStream. (.getBytes "Syntax error on line 7: PRIVATE-SOURCE" "UTF-8"))}
                 {:status 200 :headers {"content-type" "text/html"}
                  :body (ByteArrayInputStream. (.getBytes "PRIVATE-UPSTREAM" "UTF-8"))}
                 (response "image/png" (.getBytes "not an image" "UTF-8"))]]
    (with-redefs [images/image-store (atom {}) http/request (constantly reply)]
      (let [result (kroki/render! {:type "mermaid" :source "PRIVATE-SOURCE"})]
        (:result/error result) => string?
        (:result/public-error result) => map?
        (public/projection result) => nil
        (str/includes? (pr-str result) "PRIVATE") => false
        @images/image-store => {}))))

(fact "transport exceptions never expose endpoints or diagram source"
  (with-redefs [http/request (fn [_] (throw (Exception. "PRIVATE-ENDPOINT")))]
    (let [result (kroki/render! {:type "mermaid" :source "PRIVATE-SOURCE"})]
      (get-in result [:result/public-error :kind]) => "transport"
      (str/includes? (pr-str result) "PRIVATE") => false)))

(fact "cached discovery never crosses configured origins"
  (with-redefs [kroki/catalog-cache
                (atom {:origin "https://first.example" :expires-at Long/MAX_VALUE
                       :data {:engines [{:type "private-engine" :version "1"}]}})
                kroki/endpoint (constantly "https://second.example")
                http/request (fn [_] (throw (Exception. "unavailable")))]
    (let [result (kroki/types!)]
      (:result/error result) => string?
      (str/includes? (pr-str result) "private-engine") => false)))

(fact "oversized artifacts and explicit MIME mismatches fail before storage"
  (doseq [reply [(response "image/png" (byte-array (inc (* 8 1024 1024))))
                 (response "image/svg+xml" (.getBytes "<svg/>" "UTF-8"))]]
    (with-redefs [http/request (constantly reply)
                  images/store-image! (fn [_] (throw (AssertionError. "No storage")))]
      (:result/error (kroki/render! {:type "x" :source "x" :format "png"})) => string?)))

(fact "compiler locations are useful without returning arbitrary diagnostic prose"
  (with-redefs [http/request
                (constantly {:status 400 :headers {}
                             :body (ByteArrayInputStream.
                                     (.getBytes "Syntax error on line 7 column 12: PRIVATE-SOURCE" "UTF-8"))})]
    (let [result (kroki/render! {:type "mermaid" :source "PRIVATE-SOURCE"})]
      (get-in result [:result/public-error :line]) => 7
      (get-in result [:result/public-error :column]) => 12
      (get-in result [:result/public-error :kind]) => "invalid-source"
      (str/includes? (pr-str result) "PRIVATE") => false)))
