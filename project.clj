(defproject walmartlabs/vizdeps "0.1.6+"
  :description "Visualize Leiningen project dependencies using Graphviz."
  :url "https://github.com/walmartlabs/vizdeps"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 ;; Leave here: version 0.0.7 removes save! with no
                 ;; real replacement.
                 [dorothy "0.0.6"]
                 [medley "1.2.0"]
                 [com.stuartsierra/dependency "0.2.0"]
                 ;; Needed for Leinigen 2.8 and above, harmless in 2.7.1:
                 [org.clojure/tools.cli "0.4.2"]]
  :eval-in-leiningen true)
