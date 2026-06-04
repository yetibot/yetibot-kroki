(ns yetibot-kroki.commands.kroki
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [clojure.string :as string]
    [clj-http.util :refer [deflate utf8-bytes base64-encode]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn encode-for-kroki
  "kroki wants the graph encoded a specific way when part of a URL.
   See docs at https://docs.kroki.io/kroki/setup/encode-diagram/"
  [graph-string]
  (-> graph-string
      utf8-bytes
      deflate
      base64-encode
      (string/replace "+" "-")
      (string/replace "/" "_")))

(defn kroki-url
  [type graph]
  (str "https://demo.kroki.io/" type "/png/" (encode-for-kroki graph)))

(defn graphviz-url
  [graphviz]
  (kroki-url "graphviz" graphviz))

(def diagram-types
  {:blockdiag "BlockDiag"
   :bpmn "BPMN"
   :bytefield "Bytefield"
   :seqdiag "SeqDiag"
   :actdiag "ActDiag"
   :nwdiag "NwDiag"
   :packetdiag "PacketDiag"
   :rackdiag "RackDiag"
   :c4plantuml "C4 PlantUML"
   :ditaa "Ditaa"
   :erd "Erd"
   :graphviz "GraphViz"
   :mermaid "Mermaid"
   :nomnoml "Nomnoml"
   :plantuml "PlantUML"
   :svgbob "Svgbob"
   :vega "Vega"
   :vegalite "Vega-Lite"
   :wavedrom "WaveDrom"})

(defn kroki-cmd
  "kroki <type> <diagram-source> # generate a Kroki diagram of any supported type. Examples: https://kroki.io/examples.html"
  [{[_ type-str graph-string] :match :as cmd}]
  (info "kroki" cmd)
  (let [type-key (keyword (string/lower-case type-str))]
    (if (contains? diagram-types type-key)
      (kroki-url (name type-key) graph-string)
      {:result/error (format "Unsupported diagram type: `%s`. Use `kroki types` to see supported types." type-str)})))

(defn list-types-cmd
  "kroki types # list types of supported diagrams"
  [_]
  {:result/value
   (map (fn [[k v]] (format "`%s` - %s" (name k) v)) diagram-types)
   :result/data diagram-types})

(cmd-hook #"kroki"
  #"types" list-types-cmd
  #"([a-zA-Z0-9_-]+)\s+([\s\S]+)" kroki-cmd)
