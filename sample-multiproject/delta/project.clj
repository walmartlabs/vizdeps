(defproject sample/delta "0.1.0-SNAPSHOT"
  :plugins [[lein-parent "0.3.1"]]
  :parent-project {:path "../project.clj"
  :inherit [:managed-dependencies]}

  :dependencies [[sample/archie  "0.1.0-SNAPSHOT"]
  [sample/bravo "0.1.0-SNAPSHOT"]
  [ring/ring-core "1.5.1"]])