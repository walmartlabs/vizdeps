(defproject sample/big-project "0.1.0-SNAPSHOT"
    :plugins [[lein-sub "0.3.0"]]
    :managed-dependencies [[com.stuartsierra/component "0.3.2"]]
        :sub ["archie" "bravo" "gamma" "delta"])