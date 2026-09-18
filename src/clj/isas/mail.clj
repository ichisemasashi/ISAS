(ns isas.mail
  (:require [clojure.string :as str]
            [postal.core :as postal]
            [isas.log :as log]))

(def ^:dynamic *send-fn* nil)

(defn normalize-lang [lang]
  (if (= "en" (str lang)) "en" "ja"))

(defn reset-subject
  ([kind] (reset-subject kind "ja"))
  ([kind lang]
   (let [en? (= "en" (normalize-lang lang))]
     (cond
       (and en? (= kind "admin")) "ISAS admin password reset"
       en? "ISAS password reset"
       (= kind "admin") "ISAS 管理者パスワードの再設定"
       :else "ISAS パスワードの再設定"))))

(defn reset-body
  ([kind public-url token] (reset-body kind public-url token "ja"))
  ([kind public-url token lang]
   (let [path (if (= kind "admin") "/admin/reset" "/reset")
         base (str/replace public-url #"/$" "")
         link (str base path "?token=" token)
         en? (= "en" (normalize-lang lang))]
     (if en?
       (str "This is a password reset notice from ISAS.\n\n"
            "Use the link below within 24 hours to set a new password.\n"
            link "\n\n"
            "If you did not request this, you can ignore this message.")
       (str "ISAS のパスワード再設定の案内です。\n\n"
            "次のリンクから、24時間以内に新しいパスワードを決めてください。\n"
            link "\n\n"
            "心当たりが無ければ、この案内は無視してください。")))))

(defn smtp-opts [conf]
  (let [base {:host (:smtp-host conf)
              :port (:smtp-port conf)
              :tls (:smtp-starttls? conf)}]
    (if (str/blank? (:smtp-user conf))
      base
      (assoc base :user (:smtp-user conf) :pass (:smtp-password conf)))))

(defn postal-send [conf message]
  (postal/send-message (smtp-opts conf) message))

(defn send-reset! [conf {:keys [kind to token ui-lang]}]
  (let [lang (normalize-lang ui-lang)
        message {:from (:smtp-from conf)
                 :to to
                 :subject (reset-subject kind lang)
                 :body (reset-body kind (:public-url conf) token lang)}
        send! (or *send-fn* postal-send)]
    (try
      (send! conf message)
      (log/info "再設定メールを送りました" :kind kind :to to :ui-lang lang)
      true
      (catch Exception e
        (log/error "再設定メールを送れませんでした"
                   :kind kind
                   :to to
                   :ui-lang lang
                   :error (.getMessage e))
        false))))
