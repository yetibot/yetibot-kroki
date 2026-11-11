(ns yetibot-kroki.commands.kroki-test
  (:require
   [clojure.string :as string]
   [midje.sweet :refer [fact =>]]
   [yetibot-kroki.client :as client]
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
 => (str "https://kroki.io/mermaid/png/" (encode-for-kroki "graph TD; A-->B;"))

 (kroki-url "graphviz" "digraph G {}")
 => (str "https://kroki.io/graphviz/png/" (encode-for-kroki "digraph G {}"))

 (kroki-url "excalidraw" "{\"type\": \"excalidraw\"}")
 => (str "https://kroki.io/excalidraw/svg/" (encode-for-kroki "{\"type\": \"excalidraw\"}"))

 (kroki-url "BPMN" "bpmn content")
 => (str "https://kroki.io/BPMN/svg/" (encode-for-kroki "bpmn content")))

(fact "shorthand and explicit render pass literal source to the shared renderer"
  (with-redefs [client/render! identity]
    (kroki-cmd {:match ["old match" "MERMAID" "graph TD; A-->B;"]})
    => {:type "mermaid" :source "graph TD; A-->B;"}
    (kroki-cmd {:match "render graphviz --format svg -- digraph G { a -> b }"})
    => {:type "graphviz" :source "digraph G { a -> b }" :format "svg"}
    (kroki-cmd {:match "render d2 --option layout=tala -- x -> y"})
    => {:type "d2" :source "x -> y" :options {"layout" "tala"}}
    (kroki-cmd {:match "render plantuml\n@startuml\nAlice -> Bob: Hello\n@enduml"})
    => {:type "plantuml" :source "@startuml\nAlice -> Bob: Hello\n@enduml"}
    (kroki-cmd {:match "render mermaid -- --format is literal"})
    => {:type "mermaid" :source "--format is literal"}))

(fact "pipe input and one outer fence preserve the complete source"
  (with-redefs [client/render! identity]
    (kroki-cmd {:match "render mermaid" :raw "graph TD\n  A-->B\n"})
    => {:type "mermaid" :source "graph TD\n  A-->B\n"}
    (kroki-cmd {:match "mermaid ```mermaid\ngraph TD\n  A-->B\n```"})
    => {:type "mermaid" :source "graph TD\n  A-->B"}))

(fact "catalog/help/errors are structured rather than unchecked links"
  (with-redefs [client/types! (fn ([] :catalog) ([type] {:selected type}))]
    (list-types-cmd {}) => :catalog
    (kroki-cmd {:match "types"}) => :catalog
    (kroki-cmd {:match "info LINI"}) => {:selected "lini"})
  (:result/value (kroki-cmd {:match ""})) => string?
  (:result/error (kroki-cmd {:match "render mermaid --format"})) => string?)
