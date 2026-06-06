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

(def svg-only-types
  #{"bpmn" "bytefield" "excalidraw" "nomnoml" "pikchr" "svgbob" "wavedrom"})

(defn kroki-url
  [type graph]
  (let [format (if (contains? svg-only-types (string/lower-case type)) "svg" "png")]
    (str "https://kroki.io/" type "/" format "/" (encode-for-kroki graph))))

(defn graphviz-url
  [graphviz]
  (kroki-url "graphviz" graphviz))

(def diagram-types
  {:actdiag "ActDiag"
   :blockdiag "BlockDiag"
   :bpmn "BPMN"
   :bytefield "Bytefield"
   :c4plantuml "C4 PlantUML"
   :d2 "D2"
   :dbml "DBML"
   :ditaa "Ditaa"
   :erd "Erd"
   :excalidraw "Excalidraw"
   :graphviz "GraphViz"
   :mermaid "Mermaid"
   :nomnoml "Nomnoml"
   :nwdiag "NwDiag"
   :packetdiag "PacketDiag"
   :pikchr "Pikchr"
   :plantuml "PlantUML"
   :rackdiag "RackDiag"
   :seqdiag "SeqDiag"
   :structurizr "Structurizr"
   :svgbob "Svgbob"
   :symbolator "Symbolator"
   :tikz "TikZ"
   :umlet "UMLet"
   :vega "Vega"
   :vegalite "Vega-Lite"
   :wavedrom "WaveDrom"
   :wireviz "WireViz"})

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
