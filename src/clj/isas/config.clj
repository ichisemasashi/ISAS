(ns isas.config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [isas.log :as log]))

(def required-keys
  ["admin_email" "admin_password" "smtp_host" "smtp_port" "smtp_from" "public_url"])

(defn parse-line [line]
  (let [s (str/trim (or line ""))]
    (cond
      (str/blank? s) nil
      (str/starts-with? s "#") nil
      :else
      (let [i (str/index-of s "=")]
        (when-not i
          (throw (ex-info "設定の行に = がありません" {:line s :isas/reason :bad-line})))
        [(str/trim (subs s 0 i)) (str/trim (subs s (inc i)))]))))

(defn parse-text [text]
  (let [raw (->> (str/split-lines (or text ""))
                 (keep parse-line)
                 (into {}))
        missing (seq (remove #(contains? raw %) required-keys))]
    (when missing
      (throw (ex-info "設定の項目が足りません" {:key (first missing) :isas/reason :missing-key})))
    (let [port (try
                 (Integer/parseInt (get raw "smtp_port"))
                 (catch Exception _
                   (throw (ex-info "smtp_port が整数ではありません"
                                   {:value (get raw "smtp_port") :isas/reason :bad-port}))))
          starttls (let [v (get raw "smtp_starttls" "1")]
                     (not= v "0"))]
      {:admin-email (get raw "admin_email")
       :admin-password (get raw "admin_password")
       :smtp-host (get raw "smtp_host")
       :smtp-port port
       :smtp-user (get raw "smtp_user" "")
       :smtp-password (get raw "smtp_password" "")
       :smtp-from (get raw "smtp_from")
       :public-url (get raw "public_url")
       :smtp-starttls? starttls})))

(defn load-conf [path]
  (let [f (io/file path)]
    (when-not (.isFile f)
      (throw (ex-info "設定ファイルがありません" {:path path :isas/reason :missing-file})))
    (try
      (let [conf (parse-text (slurp f :encoding "UTF-8"))]
        (log/info "設定ファイルを読みました" :path path :smtp-host (:smtp-host conf))
        conf)
      (catch java.io.IOException e
        (throw (ex-info "設定ファイルが読めません"
                        {:path path :isas/reason :unreadable :cause e}))))))
