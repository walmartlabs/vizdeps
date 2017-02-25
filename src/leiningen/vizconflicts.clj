(ns leiningen.vizconflicts
  "Graphviz visualization of conflicts in a multi-module project."
  (:require
    [com.walmartlabs.vizdeps.common :as common]
    [leiningen.core.main :as main]))

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
    (prn options)))
