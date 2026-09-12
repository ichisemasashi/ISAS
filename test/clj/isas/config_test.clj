(ns isas.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [isas.config :as config]
            [isas.test-util :as tu]))

(deftest parse-line-test
  (is (nil? (config/parse-line "")))
  (is (nil? (config/parse-line "   ")))
  (is (nil? (config/parse-line "# comment")))
  (is (nil? (config/parse-line nil)))
  (is (= ["a" "b"] (config/parse-line " a = b ")))
  (is (thrown-with-msg? Exception #"=" (config/parse-line "noequals"))))

(deftest parse-text-test
  (let [c (config/parse-text tu/sample-conf)]
    (is (= "admin@example.com" (:admin-email c)))
    (is (= 587 (:smtp-port c)))
    (is (true? (:smtp-starttls? c))))
  (is (false? (:smtp-starttls? (config/parse-text (str tu/sample-conf "smtp_starttls=0\n")))))
  (is (= "" (:smtp-user (config/parse-text
                         (str "admin_email=a@b.c\nadmin_password=x\nsmtp_host=h\nsmtp_port=1\n"
                              "smtp_from=f@f.f\npublic_url=http://x\n")))))
  (is (thrown-with-msg? Exception #"足りません" (config/parse-text "admin_email=a@b.c\n")))
  (is (thrown-with-msg? Exception #"足りません" (config/parse-text nil)))
  (is (thrown-with-msg? Exception #"整数" (config/parse-text
                                          (str "admin_email=a@b.c\nadmin_password=x\nsmtp_host=h\n"
                                               "smtp_port=no\nsmtp_from=f@f.f\npublic_url=http://x\n")))))

(deftest load-conf-test
  (let [path (tu/temp-file "conf" ".conf" tu/sample-conf)
        c (config/load-conf path)]
    (is (= "smtp.example.com" (:smtp-host c))))
    (is (thrown-with-msg? Exception #"ありません" (config/load-conf "/no/such/isas.conf")))
  (is (thrown-with-msg? Exception #"読めません"
                        (with-redefs [slurp (fn [& _] (throw (java.io.IOException. "denied")))]
                          (config/load-conf (tu/temp-file "conf" ".conf" tu/sample-conf))))))
