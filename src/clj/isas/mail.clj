(ns isas.mail
  (:require [clojure.string :as str]
            [postal.core :as postal]
            [isas.log :as log]))

(def ^:dynamic *send-fn* nil)

(defn reset-subject [kind]
  (if (= kind "admin")
    "ISAS 管理者パスワードの再設定"
    "ISAS パスワードの再設定"))

(defn reset-body [kind public-url token]
  (let [path (if (= kind "admin") "/admin/reset" "/reset")
        base (str/replace public-url #"/$" "")
        link (str base path "?token=" token)]
    (str "ISAS のパスワード再設定の案内です。\n\n"
         "次のリンクから、24時間以内に新しいパスワードを決めてください。\n"
         link "\n\n"
         "心当たりが無ければ、この案内は無視してください。")))

(defn smtp-opts [conf]
  (let [base {:host (:smtp-host conf)
              :port (:smtp-port conf)
              :tls (:smtp-starttls? conf)}]
    (if (str/blank? (:smtp-user conf))
      base
      (assoc base :user (:smtp-user conf) :pass (:smtp-password conf)))))

(defn postal-send [conf message]
  (postal/send-message (smtp-opts conf) message))

(defn send-reset! [conf {:keys [kind to token]}]
  (let [message {:from (:smtp-from conf)
                 :to to
                 :subject (reset-subject kind)
                 :body (reset-body kind (:public-url conf) token)}
        send! (or *send-fn* postal-send)]
    (try
      (send! conf message)
      (log/info "再設定メールを送りました" :kind kind :to to)
      true
      (catch Exception e
        (log/error "再設定メールを送れませんでした"
                   :kind kind
                   :to to
                   :error (.getMessage e))
        false))))
