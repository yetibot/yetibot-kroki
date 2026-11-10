(ns yetibot-kroki.commands.kroki
  (:require [clojure.string :as string]
            [clj-http.util :refer [deflate utf8-bytes base64-encode]]
            [yetibot-kroki.client :as client]
            [yetibot.core.hooks :refer [cmd-hook]]))

(defn encode-for-kroki
  "Encode source for legacy share URLs. Encoding does not make source private."
  [graph-string]
  (-> graph-string utf8-bytes deflate base64-encode
      (string/replace "+" "-") (string/replace "/" "_")))

(defn kroki-url
  "Legacy unchecked URL helper, retained for external callers only.
   Commands and native tools use client/render! and server negotiation instead."
  [type graph]
  (let [format (if (contains? #{"bpmn" "bytefield" "excalidraw" "nomnoml"
                                "pikchr" "svgbob" "wavedrom"}
                              (string/lower-case type)) "svg" "png")]
    (str "https://kroki.io/" type "/" format "/" (encode-for-kroki graph))))

(defn graphviz-url [graphviz] (kroki-url "graphviz" graphviz))

(def help-text
  (str "kroki types — discover available diagram engines\n"
       "kroki info TYPE — inspect the discovered engine/version\n"
       "kroki [render] TYPE [--format FORMAT] [--option KEY=VALUE] [--] SOURCE\n"
       "Example: kroki render d2 --option layout=tala -- x -> y\n"
       "Source may be multiline, fenced code, or piped text. Auto prefers PNG, "
       "then SVG. Other formats depend on the engine.\n"
       "Source is sent to the configured Kroki service (default kroki.io). "
       "Generated artifacts are public, temporary downloads, not private storage.\n"
       "Examples/options: https://docs.kroki.io/kroki/setup/usage/"))

(defn list-types-cmd
  "kroki types # discover supported diagram types and versions"
  [_]
  (client/types!))

(defn- token [text]
  (when-let [[_ word rest] (re-matches #"(?s)^(\S+)(?:[ \t]+|\r?\n)?(.*)$" text)]
    [word rest]))

(defn- invalid! []
  (throw (ex-info "Invalid Kroki command." {:kind "invalid-arguments"})))

(defn- render-request [text {:keys [raw opts]}]
  (let [[type remaining] (or (token text) (invalid!))]
    (loop [text remaining request {:type (string/lower-case type)}]
      (cond
        (or (= text "--") (string/starts-with? text "-- ")
            (string/starts-with? text "--\n") (string/starts-with? text "--\r\n"))
        (assoc request :source (second (token text)))

        (re-find #"^--(?:format|option)(?:\s|$)" text)
        (let [[flag remaining] (token text)
              [value remaining] (or (token remaining) (invalid!))]
          (case flag
            "--format" (do (when (:format request) (invalid!))
                           (recur remaining (assoc request :format value)))
            "--option" (let [[_ k v] (or (re-matches #"([^=]+)=(.*)" value) (invalid!))]
                         (when (contains? (:options request) k) (invalid!))
                         (recur remaining (assoc-in request [:options k] v)))))

        :else
        (assoc request :source
               (if (string/blank? text)
                 (cond (and (string? raw) (not (string/blank? raw))) raw
                       (and (sequential? opts) (every? string? opts)) (string/join "\n" opts)
                       :else text)
                 text))))))

(defn- unfence [source]
  (if-let [[_ _ body] (re-matches #"(?s)[ \t]*(`{3,}|~{3,})[A-Za-z0-9_-]*\r?\n(.*?)\r?\n\1[ \t]*\r?\n?" source)]
    body source))

(defn kroki-cmd
  "kroki [render] <type> [--format F] [--option K=V] [--] <source> # render a verified diagram; pipe text or use fenced/multiline source. kroki types discovers engines."
  [{:keys [match] :as args}]
  (try
    (let [text (string/triml (if (vector? match)
                               (str (second match) " " (nth match 2 ""))
                               (or match "")))
          [verb rest] (token text)]
      (cond
        (string/blank? text) {:result/value help-text}
        (= text "types") (list-types-cmd args)
        (= verb "info") (client/types! (string/lower-case (string/trim rest)))
        :else
        (let [request (render-request (if (= verb "render") rest text) args)]
          (client/render! (update request :source unfence)))))
    (catch Exception _ (client/failure "invalid-arguments" "arguments"))))

(cmd-hook #"kroki" #"[\s\S]*" kroki-cmd)
