(ns yetibot-kroki.client
  "Discovery and verified rendering, shared by commands and native agent tools."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [yetibot.core.config :as config]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.public-result :as public]
            [yetibot.core.webapp.routes.images :as images])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net URI]
           [java.time Instant]
           [java.util Base64]))

(def max-source-bytes 262144)
(def ^:private max-response-bytes (* 8 1024 1024))
(def ^:private cache-ms (* 5 60 1000))
(defonce catalog-cache (atom nil))

(def formats
  {"png" "image/png" "svg" "image/svg+xml" "jpeg" "image/jpeg"
   "pdf" "application/pdf" "txt" "text/plain" "utxt" "text/plain"
   "base64" "text/plain"})

(defn endpoint []
  (let [url (or (:value (config/get-config string? [:kroki :url])) "https://kroki.io")
        uri (try (URI. url) (catch Exception _ nil))]
    (when-not (and uri (contains? #{"http" "https"} (.getScheme uri))
                   (.getHost uri) (nil? (.getUserInfo uri))
                   (nil? (.getQuery uri)) (nil? (.getFragment uri)))
      (throw (ex-info "Invalid Kroki endpoint configuration." {:kind "configuration"})))
    (str/replace url #"/+$" "")))

(defn type-name? [s]
  (and (string? s) (boolean (re-matches #"[a-z][a-z0-9_-]{0,63}" s))))

(defn validate-render! [{:keys [type source format options] :as request}]
  (when-not (and (map? request)
                 (every? #{:type :source :format :options} (keys request))
                 (type-name? type) (string? source) (not (str/blank? source))
                 (<= (count source) max-source-bytes)
                 (<= (alength (.getBytes ^String source "UTF-8")) max-source-bytes)
                 (or (nil? format) (= "auto" format) (contains? formats format))
                 (or (nil? options)
                     (and (map? options) (<= (count options) 32)
                          (every? (fn [[k v]]
                                    (and (string? k)
                                         (re-matches #"[A-Za-z][A-Za-z0-9_-]{0,63}" k)
                                         (or (boolean? v)
                                             (and (number? v) (Double/isFinite (double v)))
                                             (and (string? v) (<= (count v) 2048)))))
                                  options))))
    (throw (ex-info "Invalid Kroki type, source, format or options."
                    {:kind "invalid-arguments"})))
  request)

(defn failure
  "A reviewed error without source, upstream prose, headers or exceptions."
  ([kind stage] (failure kind stage nil))
  ([kind stage status]
   (let [message (get {"invalid-arguments" "Invalid Kroki arguments. Use kroki for help."
                      "configuration" "Kroki endpoint configuration is invalid."
                      "invalid-source" "Kroki rejected the diagram source or options."
                      "unsupported-type" "Kroki does not support this diagram type. Use kroki types."
                      "unsupported-format" "Kroki does not support this output format for this type."
                      "too-large" "The Kroki request or result exceeded the size limit."
                      "invalid-response" "Kroki returned an invalid response."
                      "transport" "Could not reach Kroki; no diagram artifact was produced."
                      "storage" "The diagram could not be stored for delivery."
                      "unavailable" "Kroki is unavailable."}
                     kind "Kroki failed to complete the request.")
         diagnostic (cond-> {:service "kroki" :stage stage :kind kind}
                      (and (integer? status) (<= 100 status 599))
                      (assoc :http-status status))]
     (public/reviewed {:result/error message :result/data {:error diagnostic}
                       :result/public-error diagnostic}))))

(defn- bounded-bytes [^InputStream stream maximum deadline]
  (with-open [in stream out (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (when (>= (System/nanoTime) deadline)
          (throw (ex-info "Kroki response timed out." {:kind "transport"})))
        (let [n (.read in buffer)]
          (if (= -1 n)
            (.toByteArray out)
            (let [total (+ total n)]
              (when (> total maximum)
                (throw (ex-info "Kroki response exceeded the size limit."
                                {:kind "too-large"})))
              (.write out buffer 0 n)
              (recur total))))))))

(defn- request! [method url options maximum]
  (let [deadline (+ (System/nanoTime) (* 30 1000000000))
        response (http/request
                   (merge {:method method :url url :as :stream
                           :throw-exceptions false :redirect-strategy :none
                           :retry-handler (constantly false)
                           :conn-timeout 5000 :socket-timeout 20000
                           :connection-request-timeout 5000}
                          options))]
    (when-not (instance? InputStream (:body response))
      (throw (ex-info "Invalid Kroki response stream." {:kind "invalid-response"})))
    (assoc response :body (bounded-bytes (:body response) maximum deadline))))

(defn- mime-type [response]
  (some-> (get-in response [:headers "content-type"])
          (str/split #";" 2) first str/trim str/lower-case))

(defn- catalog! []
  (let [origin (endpoint) now (System/currentTimeMillis)]
    (locking catalog-cache
      (let [cached (when (= origin (:origin @catalog-cache)) @catalog-cache)]
        (if (and cached (< now (:expires-at cached)))
          (:data cached)
          (try
            (let [response (request! :get (str origin "/health") {} 262144)
                  _ (when-not (= 200 (:status response))
                      (throw (ex-info "Kroki discovery failed."
                                      {:kind "unavailable" :status (:status response)})))
                  health (try (json/parse-string (String. ^bytes (:body response) "UTF-8"))
                              (catch Exception _
                                (throw (ex-info "Invalid Kroki discovery JSON."
                                                {:kind "invalid-response"}))))
                  versions (get health "version")
                  _ (when-not (and (map? versions) (<= 1 (count versions) 1024)
                                   (map? (get versions "kroki")))
                      (throw (ex-info "Invalid Kroki discovery response." {:kind "invalid-response"})))
                  engines (->> (dissoc versions "kroki")
                               (keep (fn [[type version]]
                                       (when (and (type-name? type) (string? version)
                                                  (<= (count version) 128)
                                                  (not (re-find #"\p{Cntrl}" version)))
                                         {:type type :version version})))
                               (sort-by :type) vec)
                  _ (when-not (= (count engines) (dec (count versions)))
                      (throw (ex-info "Invalid engine metadata." {:kind "invalid-response"})))
                  version (get-in versions ["kroki" "number"])
                  data {:engines engines
                        :server-version (when (and (string? version)
                                                    (re-matches #"[A-Za-z0-9.+_-]{1,128}" version)) version)
                        :checked-at (str (Instant/ofEpochMilli now)) :stale false}]
              (reset! catalog-cache {:origin origin :expires-at (+ now cache-ms) :data data})
              data)
            (catch Exception e
              (if cached
                (let [data (assoc (:data cached) :stale true)]
                  (reset! catalog-cache {:origin origin :expires-at (+ now 30000) :data data})
                  data)
                (throw e)))))))))

(defn types!
  "Discover available engines, optionally selecting one. No rendering."
  ([] (types! nil))
  ([type]
   (try
     (when (and type (not (type-name? type)))
       (throw (ex-info "Invalid diagram type." {:kind "invalid-arguments"})))
     (let [data (catalog!)
           data (cond-> data type (update :engines #(filterv (fn [e] (= type (:type e))) %)))
           rows (mapv #(str (:type %) " — " (:version %)
                            (when (:stale data) " (cached; discovery unavailable)"))
                      (:engines data))]
       (public/reviewed
         {:result/value (if (seq rows) rows ["No matching Kroki engines were discovered."])
          :result/data data :result/collection-path [:engines]
          :result/public-data
          (mapv #(merge % (select-keys data [:server-version :checked-at :stale]))
                (:engines data))}))
     (catch Exception e
       (failure (or (:kind (ex-data e)) "transport") "discovery" (:status (ex-data e)))))))

(defn- render-format [response requested]
  (let [mime (mime-type response)
        format (if (and requested (not= "auto" requested))
                 (when (= mime (get formats requested)) requested)
                 (get {"image/png" "png" "image/svg+xml" "svg"} mime))
        body ^bytes (:body response)
        prefix (String. body 0 (min 4096 (alength body)) "UTF-8")
        valid? (case format
                 "png" (and (>= (alength body) 8)
                            (= [137 80 78 71 13 10 26 10]
                               (mapv #(bit-and 255 %) (take 8 body))))
                 "jpeg" (and (>= (alength body) 3)
                             (= [255 216 255] (mapv #(bit-and 255 %) (take 3 body))))
                 "svg" (boolean (re-find #"<svg(?:\s|/?>)" prefix))
                 "pdf" (str/starts-with? prefix "%PDF-")
                 (and format (not (str/blank? prefix))))]
    (when-not valid?
      (throw (ex-info "Invalid diagram artifact." {:kind "invalid-response"})))
    format))

(defn render!
  "Render once and retain the verified artifact. Never publishes diagram source."
  [request]
  (try
    (validate-render! request)
    (let [{:keys [type source format options]} request
          explicit? (and format (not= "auto" format))
          response (request! :post (str (endpoint) "/" type
                                       (when explicit? (str "/" format)))
                             {:content-type :json
                              ;; Public 0.36.0 selected SVG for PNG,SVG;q=0.9.
                              ;; Equal-weight ordered alternatives retain PNG
                              ;; previews while still supporting SVG-only engines.
                              :headers {"Accept" (if explicit? (get formats format)
                                                     "image/png, image/svg+xml")}
                              :body (json/generate-string
                                      {:diagram_source source :diagram_options (or options {})})}
                             max-response-bytes)
          status (:status response)]
      (if-not (= status 200)
        (let [body (String. ^bytes (:body response) "UTF-8")
              kind (cond
                     (= status 413) "too-large"
                     (and (= status 400) (re-find #"(?i)unsupported diagram type" body)) "unsupported-type"
                     (and (= status 400) (re-find #"(?i)unsupported (?:output )?format|unsupported mime" body)) "unsupported-format"
                     (= status 400) "invalid-source"
                     :else "unavailable")]
          (let [position (into {}
                               (keep (fn [[key pattern]]
                                       (when-let [[_ n] (re-find pattern body)]
                                         [key (Long/parseLong n)])))
                               [[:line #"(?i)\bline[ :]+([0-9]{1,7})\b"]
                                [:column #"(?i)\bcolumn[ :]+([0-9]{1,7})\b"]])
                result (failure kind "render" status)]
            (-> result
                (update :result/public-error merge position)
                (update-in [:result/data :error] merge position))))
        (let [format (render-format response format)
              mime (get formats format)
              body ^bytes (:body response)
              id (try (images/store-image!
                         {:data (.encodeToString (Base64/getEncoder) body) :mime-type mime})
                      (catch Exception _
                        (throw (ex-info "Artifact storage failed." {:kind "storage"}))))
              data {:type type :format format :mime-type mime :bytes (alength body)
                    :id id :url (str (gemini/yetibot-base-url) "/generated-images/" id "." format)
                    :ephemeral true}]
          (public/reviewed {:result/value (str type " diagram\n" (:url data))
                            :result/data data :result/public-data data}))))
    (catch Exception e
      (failure (or (:kind (ex-data e)) "transport") "render"))))
