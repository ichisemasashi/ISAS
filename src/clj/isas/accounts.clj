(ns isas.accounts
  (:require [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.log :as log]
            [isas.mail :as mail]
            [isas.time :as time]))

(defn bootstrap-admin! [ds conf]
  (let [n (db/count-admins ds)
        email (crypto/normalize-email (:admin-email conf))
        password (:admin-password conf)]
    (cond
      (pos? n)
      (do
        (log/info "管理者は既にあるので設定ファイルでは作りません" :count n)
        {:status :exists :count n})

      (or (empty? email) (empty? password))
      (throw (ex-info "管理者が0人なので admin_email と admin_password が要ります"
                      {:isas/reason :missing-bootstrap}))

      (db/find-user-by-email ds email)
      (throw (ex-info "admin_email は既に利用者です"
                      {:email email :isas/reason :admin-is-user}))

      :else
      (let [row (db/insert-admin! ds email (crypto/hash-secret password))]
        (log/info "最初の管理者を設定ファイルから作りました" :email email :id (:id row))
        {:status :created :id (:id row) :email email}))))

(defn session-valid? [ds kind cookie-id]
  (when (seq cookie-id)
    (when-let [sess (db/find-session ds cookie-id)]
      (when (and (= kind (:kind sess))
                 (nil? (:revoked_at sess))
                 (time/before? (time/now-utc) (:expires_at sess)))
        (let [account (if (= kind "user")
                        (db/find-user-by-id ds (:account_id sess))
                        (db/find-admin-by-id ds (:account_id sess)))]
          (when (and account
                     (or (= kind "admin") (nil? (:revoked_at account))))
            {:session sess :account account}))))))

(defn current-session [sys kind cookie-id]
  (session-valid? (:ds sys) kind cookie-id))

(defn account-ui-lang [account]
  (let [v (some-> account :ui_lang str)]
    (if (#{"ja" "en"} v) v "ja")))

(defn login [sys kind email password]
  (let [ds (:ds sys)
        e (crypto/normalize-email email)
        account (if (= kind "user")
                  (db/find-user-by-email ds e)
                  (db/find-admin-by-email ds e))
        ok? (and account
                 (or (= kind "admin") (nil? (:revoked_at account)))
                 (crypto/check-secret password (:password_hash account)))]
    (if-not ok?
      (do
        (log/warn "ログインできませんでした" :kind kind :email e)
        {:ok false :code "login_failed"})
      (let [sid (crypto/session-id)
            lang (account-ui-lang account)]
        (db/insert-session! ds {:id sid
                                :kind kind
                                :account-id (:id account)
                                :expires-at (time/plus-days 14)})
        (log/info "ログインしました" :kind kind :email e :account-id (:id account) :ui-lang lang)
        {:ok true :session-id sid :email (:email account) :ui_lang lang}))))

(defn logout [sys kind cookie-id]
  (if-let [ctx (session-valid? (:ds sys) kind cookie-id)]
    (do
      (db/revoke-session! (:ds sys) cookie-id)
      (log/info "ログアウトしました" :kind kind :account-id (get-in ctx [:account :id]))
      {:ok true})
    {:ok false :code "unauthorized"}))

(defn invite [sys actor-kind actor-id email]
  (let [e (crypto/normalize-email email)]
    (cond
      (not (crypto/email-ok? email))
      (do (log/warn "招待のメールが不正です" :email email :by-kind actor-kind :by-id actor-id)
          {:ok false :code "invite_invalid_email"})

      (db/find-admin-by-email (:ds sys) e)
      (do (log/warn "管理者メールは招待できません" :email e :by-kind actor-kind :by-id actor-id)
          {:ok false :code "invite_duplicate_admin"})

      :else
      (let [ds (:ds sys)
            existing (db/find-user-by-email ds e)]
        (cond
          (and existing (nil? (:revoked_at existing)))
          (do (log/warn "既に招待済みの利用者です" :email e :by-kind actor-kind :by-id actor-id)
              {:ok false :code "invite_duplicate_user"})

          existing
          (let [pw (crypto/initial-password)
                uid (:id existing)]
            (fields/clear-user-data sys uid)
            (db/reinvite-user! ds uid (crypto/hash-secret pw) actor-kind actor-id)
            (log/info "取り消した利用者を再招待しました"
                      :email e :by-kind actor-kind :by-id actor-id :user-id uid)
            {:ok true :initial_password pw :email e})

          :else
          (let [pw (crypto/initial-password)
                row (db/insert-user! ds {:email e
                                         :password-hash (crypto/hash-secret pw)
                                         :invited-by-kind actor-kind
                                         :invited-by-id actor-id})]
            (log/info "利用者を招待しました"
                      :email e :by-kind actor-kind :by-id actor-id :user-id (:id row))
            {:ok true :initial_password pw :email e}))))))

(defn list-users [sys]
  {:ok true
   :users (mapv #(select-keys % [:id :email]) (db/list-active-users (:ds sys)))})

(defn revoke-user [sys user-id]
  (let [ds (:ds sys)
        user (when user-id (db/find-user-by-id ds user-id))]
    (if (and user (nil? (:revoked_at user)))
      (do
        (fields/clear-user-data sys user-id)
        (db/revoke-user! ds user-id)
        (db/revoke-user-sessions! ds user-id)
        (log/info "利用者の招待を取り消しました" :user-id user-id :email (:email user))
        {:ok true})
      (do
        (log/info "取消しは何もしませんでした" :user-id user-id)
        {:ok true}))))

(defn change-password [sys kind account password current-password password-confirm]
  (cond
    (not= password password-confirm)
    (do (log/warn "パスワード確認が一致しません" :kind kind :account-id (:id account))
        {:ok false :code "password_mismatch"})

    (< (count (or password "")) 8)
    (do (log/warn "パスワードが短すぎます" :kind kind :account-id (:id account))
        {:ok false :code "password_too_short"})

    (not (crypto/check-secret current-password (:password_hash account)))
    (do (log/warn "現在のパスワードが違います" :kind kind :account-id (:id account))
        {:ok false :code "password_wrong"})

    :else
    (do
      (if (= kind "user")
        (db/update-user-password! (:ds sys) (:id account) (crypto/hash-secret password))
        (db/update-admin-password! (:ds sys) (:id account) (crypto/hash-secret password)))
      (log/info "パスワードを変更しました" :kind kind :account-id (:id account))
      {:ok true})))

(defn request-reset
  ([sys kind email] (request-reset sys kind email nil))
  ([sys kind email ui-lang]
   (let [e (crypto/normalize-email email)
         ds (:ds sys)
         account (if (= kind "user")
                   (db/find-user-by-email ds e)
                   (db/find-admin-by-email ds e))
         eligible? (and account (or (= kind "admin") (nil? (:revoked_at account))))
         lang (mail/normalize-lang ui-lang)]
     (if-not eligible?
       (log/info "再設定案内は送りません" :kind kind :email e :ui-lang lang)
       (let [token (crypto/reset-token)]
         (db/invalidate-reset-tokens! ds kind (:id account))
         (db/insert-reset-token! ds {:kind kind
                                     :account-id (:id account)
                                     :token-hash (crypto/hash-secret token)
                                     :expires-at (time/plus-hours 24)})
         (mail/send-reset! (:conf sys) {:kind kind :to (:email account) :token token :ui-lang lang})
         (log/info "再設定案内を送りました" :kind kind :email e :ui-lang lang)))
     {:ok true})))

(defn set-language [sys kind account ui-lang]
  (let [raw (str ui-lang)]
    (if-not (#{"ja" "en"} raw)
      (do
        (log/warn "UI言語が不正です" :kind kind :account-id (:id account) :ui-lang raw)
        {:ok false :code "lang_invalid"})
      (do
        (if (= kind "user")
          (db/update-user-ui-lang! (:ds sys) (:id account) raw)
          (db/update-admin-ui-lang! (:ds sys) (:id account) raw))
        (log/info "UI言語を変えました" :kind kind :account-id (:id account) :ui-lang raw)
        {:ok true :ui_lang raw}))))

(defn- match-token [ds kind token]
  (when (seq token)
    (->> (db/open-reset-tokens ds kind)
         (filter #(and (time/before? (time/now-utc) (:expires_at %))
                       (crypto/check-secret token (:token_hash %))))
         first)))

(defn complete-reset [sys kind token password password-confirm]
  (cond
    (not= password password-confirm)
    (do (log/warn "再設定のパスワード確認が一致しません" :kind kind)
        {:ok false :code "password_mismatch"})

    (< (count (or password "")) 8)
    (do (log/warn "再設定のパスワードが短すぎます" :kind kind)
        {:ok false :code "password_too_short"})

    :else
    (let [row (match-token (:ds sys) kind token)]
      (if-not row
        (do
          (log/warn "再設定トークンが使えません" :kind kind)
          {:ok false :code "reset_invalid"})
        (let [account (if (= kind "user")
                        (db/find-user-by-id (:ds sys) (:account_id row))
                        (db/find-admin-by-id (:ds sys) (:account_id row)))]
          (if (or (nil? account)
                  (and (= kind "user") (:revoked_at account)))
            (do
              (log/warn "再設定の対象アカウントが使えません" :kind kind :account-id (:account_id row))
              {:ok false :code "reset_invalid"})
            (do
              (if (= kind "user")
                (db/update-user-password! (:ds sys) (:id account) (crypto/hash-secret password))
                (db/update-admin-password! (:ds sys) (:id account) (crypto/hash-secret password)))
              (db/mark-token-used! (:ds sys) (:id row))
              (log/info "パスワードを再設定しました" :kind kind :account-id (:id account))
              {:ok true})))))))
