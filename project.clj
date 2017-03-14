(defproject walmartlabs/vizdeps "0.1.5"
  :description "Visualize Leiningen project dependencies using Graphviz."
  :url "https://github.com/walmartlabs/vizdeps"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [dorothy "0.0.6"]
                 [medley "0.8.4"]
                 [com.stuartsierra/dependency "0.2.0"]]
  :eval-in-leiningen true)
