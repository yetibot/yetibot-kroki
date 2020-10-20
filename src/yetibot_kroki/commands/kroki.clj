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

(defn graphviz-url
  [graphviz]
  (str "https://demo.kroki.io/graphviz/png/"
       (encode-for-kroki graphviz)))

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

(defn graphviz-cmd
  "kroki <type> <graph> # generate a graphviz graph. Examples: https://kroki.io/examples.html"
  [{[_ graphviz] :match :as cmd}]
  (info "kroki" cmd)
  (graphviz-url graphviz))

;; TODO
;; - consider supporting all formats
;; - multi line commands

(defn list-types-cmd
  "kroki types # list types of supported diagrams"
  [_]
  {:result/value
   (map (fn [[k v]] (format "`%s` - %s" (name k) v)) diagram-types)
   :result/data diagram-types})

(cmd-hook #"kroki"
  #"types" list-types-cmd
  #"graphviz\s+(.+)" graphviz-cmd)

