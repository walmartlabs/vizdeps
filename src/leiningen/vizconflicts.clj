(ns leiningen.vizconflicts
  "Graphviz visualization of conflicts in a multi-module project."
  (:require
    [com.walmartlabs.vizdeps.common :as common]
    [clojure.pprint :refer [pprint]]
    [leiningen.core.project :as project]))

(defn ^:private projects-map
  "Generates a map from sub module name (string)
   to initialized project."
  [root-project]
  (reduce (fn [m module-name]
            (assoc m module-name
                   (-> (str module-name "/project.clj")
                       project/read
                       project/init-project)))
          {}
          (:sub root-project)))


(def ^:private cli-options
  [(common/cli-output-file "target/conflicts.pdf")
   common/cli-save-dot
   common/cli-no-view
   common/cli-help])

(defn vizconflicts
  "Identify an visualizxe dependency conflicts across a multi-module project."
  {:pass-through-help true}
  [project & args]
  (when-let [options (common/parse-cli-options "vizconflicts" cli-options args)]
    (->> project
         projects-map
         (common/map-vals common/flatten-dependencies)
         pprint)))
