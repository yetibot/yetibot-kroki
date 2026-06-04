(ns yetibot-kroki.commands.kroki-test
  (:require
   [clojure.string :as string]
   [midje.sweet :refer [fact => contains]]
   [yetibot-kroki.commands.kroki :refer [encode-for-kroki
                                         kroki-url
                                         kroki-cmd
                                         list-types-cmd]]))

(def graphviz
  "
    digraph D {
      subgraph cluster_p {
        label = \"Kroki\";
        subgraph cluster_c1 {
          label = \"Server\";
          Filebeat;
          subgraph cluster_gc_1 {
            label = \"Docker/Server\";
            Java;
          }
          subgraph cluster_gc_2 {
            label = \"Docker/Mermaid\";
            \"Node.js\";
            \"Puppeteer\";
            \"Chrome\";
          }
        }
        subgraph cluster_c2 {
          label = \"CLI\";
          Golang;
        }
      }
    }
  ")

(fact
 "graphviz is properly encoded for kroki"
 (encode-for-kroki graphviz)
 => "eJyFkMsKg0AMRfd-RZgPaKlb6Upp6ZNCP0DGMejUsTPEx6b4730IMjpSs0jghntuiAefSmVG3OQQwcuDX1VN0ktCNVWNFJthBaB4ggq2wE6kC8mCYeG4xMayWcY7UotkOQF2UmGCvLY1h5eJeEy0mJEWBdJ6Bg1w5C23lW4hxF8IuSCVXKaTFHbVKa4e1VS-NcZgjc5VLMxJl8jmL-v-vNWff2t4Poxge634MwscZD-__Q1cp3xv")

(fact
 "kroki-url generates expected kroki api urls"
 (kroki-url "mermaid" "graph TD; A-->B;")
 => (str "https://demo.kroki.io/mermaid/png/" (encode-for-kroki "graph TD; A-->B;"))

 (kroki-url "graphviz" "digraph G {}")
 => (str "https://demo.kroki.io/graphviz/png/" (encode-for-kroki "digraph G {}")))

(fact
 "kroki-cmd handles valid, invalid, and multiline diagrams"
 ;; valid diagram type
 (kroki-cmd {:match ["kroki graphviz digraph G {}" "graphviz" "digraph G {}"]})
 => (str "https://demo.kroki.io/graphviz/png/" (encode-for-kroki "digraph G {}"))

 ;; invalid diagram type
 (kroki-cmd {:match ["kroki non-existent digraph G {}" "non-existent" "digraph G {}"]})
 => {:result/error "Unsupported diagram type: `non-existent`. Use `kroki types` to see supported types."}

 ;; case insensitivity for diagram types
 (kroki-cmd {:match ["kroki MERMAID graph TD; A-->B;" "MERMAID" "graph TD; A-->B;"]})
 => (str "https://demo.kroki.io/mermaid/png/" (encode-for-kroki "graph TD; A-->B;"))

 ;; multi-line diagram support
 (kroki-cmd {:match ["kroki plantuml\n@startuml\nAlice -> Bob: Hello\n@enduml" "plantuml" "@startuml\nAlice -> Bob: Hello\n@enduml"]})
 => (str "https://demo.kroki.io/plantuml/png/" (encode-for-kroki "@startuml\nAlice -> Bob: Hello\n@enduml")))

(fact
 "list-types-cmd returns all supported diagram types"
 (let [res (list-types-cmd {})]
   (:result/data res) => (contains {:graphviz "GraphViz", :mermaid "Mermaid", :excalidraw "Excalidraw", :d2 "D2", :wireviz "WireViz"})
   (count (:result/value res)) => 27))
