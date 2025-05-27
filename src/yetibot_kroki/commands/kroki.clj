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

(defn diagram-url
  "Generate a kroki URL for any supported diagram type"
  [diagram-type diagram-content]
  (let [type-name (name diagram-type)]
    (str "https://demo.kroki.io/" type-name "/png/"
         (encode-for-kroki diagram-content))))

(defn diagram-cmd
  "kroki <type> <diagram> # generate a diagram of the specified type"
  [{[_ type-str diagram-content] :match :as cmd}]
  (info "kroki" cmd)
  (let [diagram-type (keyword (string/lower-case type-str))]
    (if (contains? diagram-types diagram-type)
      (diagram-url diagram-type diagram-content)
      {:result/error (format "Unsupported diagram type: %s. Use `kroki types` to see supported types." type-str)})))

(defn list-types-cmd
  "kroki types # list types of supported diagrams"
  [_]
  {:result/value
   (map (fn [[k v]] (format "`%s` - %s" (name k) v)) diagram-types)
   :result/data diagram-types})

(defn help-cmd
  "kroki help # show usage examples"
  [_]
  {:result/value
   ["Usage: `kroki <type> <diagram-content>`"
    ""
    "Examples:"
    "• `kroki graphviz digraph { A -> B }`"
    "• `kroki mermaid graph TD; A-->B`"
    "• `kroki plantuml @startuml; Alice -> Bob; @enduml`"
    ""
    "Use `kroki types` to see all supported diagram types."
    "More examples: https://kroki.io/examples.html"]})

;; Create dynamic regex pattern that matches all diagram types
(def diagram-types-pattern
  (str "(" (string/join "|" (map name (keys diagram-types))) ")\\s+(.+)"))

(cmd-hook #"kroki"
  #"types" list-types-cmd
  #"help" help-cmd
  (re-pattern diagram-types-pattern) diagram-cmd)
