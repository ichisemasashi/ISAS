(ns isas.mail-test
  (:require [clojure.test :refer [deftest is]]
            [isas.mail :as mail]
            [isas.test-util :as tu]
            [postal.core :as postal]))

(deftest subject-and-body-test
  (is (= "ISAS パスワードの再設定" (mail/reset-subject "user")))
  (is (= "ISAS 管理者パスワードの再設定" (mail/reset-subject "admin")))
  (is (= "ISAS password reset" (mail/reset-subject "user" "en")))
  (is (= "ISAS admin password reset" (mail/reset-subject "admin" "en")))
  (is (re-find #"http://localhost:8080/reset\?token=abc"
               (mail/reset-body "user" "http://localhost:8080/" "abc")))
  (is (re-find #"/admin/reset\?token=z" (mail/reset-body "admin" "http://x" "z")))
  (is (re-find #"24 hours" (mail/reset-body "user" "http://x/" "t" "en"))))

(deftest smtp-opts-test
  (let [c (assoc (:conf (tu/test-system)) :smtp-user "" :smtp-password "")]
    (is (nil? (:user (mail/smtp-opts c))))
    (is (some? (:user (mail/smtp-opts (:conf (tu/test-system)))))))
  (is (contains? (mail/smtp-opts {:smtp-host "h" :smtp-port 1 :smtp-starttls? true :smtp-user ""}) :tls)))

(deftest send-reset-test
  (let [conf (:conf (tu/test-system))
        sent (atom [])]
    (binding [mail/*send-fn* (fn [_ m] (swap! sent conj m) {:error :SUCCESS})]
      (is (true? (mail/send-reset! conf {:kind "user" :to "a@b.c" :token "t"})))
      (is (= "a@b.c" (:to (first @sent)))))
    (binding [mail/*send-fn* (fn [_ _] (throw (Exception. "smtp down")))]
      (is (false? (mail/send-reset! conf {:kind "admin" :to "a@b.c" :token "t"}))))
    (with-redefs [postal/send-message (fn [opts msg]
                                        (is (map? opts))
                                        (is (string? (:subject msg)))
                                        {:error :SUCCESS})]
      (is (some? (mail/postal-send conf {:from "f" :to "t" :subject "s" :body "b"})))
      (is (true? (mail/send-reset! conf {:kind "user" :to "a@b.c" :token "t"}))))))
