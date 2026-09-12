(ns isas.log
  "ISAS の操作ログ。画面には出さない。テストから捕捉できる。"
  (:require [clojure.tools.logging :as logging]))

(def ^:dynamic *tap* nil)

(defn- emit [level msg data]
  (let [entry (assoc data :msg msg :level level)]
    (when-let [tap *tap*]
      (tap entry))
    (let [line (pr-str (dissoc entry :level))]
      (case level
        :info (logging/info line)
        :warn (logging/warn line)
        :error (logging/error line)
        (logging/info line)))))

(defn info [msg & {:as data}]
  (emit :info msg (or data {})))

(defn warn [msg & {:as data}]
  (emit :warn msg (or data {})))

(defn error [msg & {:as data}]
  (emit :error msg (or data {})))
