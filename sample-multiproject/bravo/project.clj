(defproject sample/bravo "0.1.0-SNAPSHOT"
  :plugins [[lein-parent "0.3.1"]]
  :parent-project {:path "../project.clj"
                   :inherit [:managed-dependencies]}
  :dependencies [[com.stuartsierra/component]
                 [ring/ring-core "1.5.0"]
                 [commons-codec "1.6"]])