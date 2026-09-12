(ns isas.log-test
  (:require [clojure.test :refer [deftest is]]
            [isas.log :as log]))

(deftest log-levels-test
  (let [got (atom [])]
    (binding [log/*tap* #(swap! got conj %)]
      (log/info "i" :k 1)
      (log/warn "w")
      (log/error "e" :err "x"))
    (is (= [:info :warn :error] (map :level @got)))
    (is (= "i" (:msg (first @got))))
    (is (= 1 (:k (first @got)))))
  (log/info "no-tap")
  (log/warn "no-tap-w")
  (log/error "no-tap-e")
  (#'log/emit :debug "d" {}))
