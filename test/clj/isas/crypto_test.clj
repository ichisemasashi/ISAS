(ns isas.crypto-test
  (:require [clojure.test :refer [deftest is]]
            [isas.crypto :as crypto])
  (:import [java.util Random]))

(deftest normalize-and-email-test
  (is (= "a@b.c" (crypto/normalize-email "  A@B.C  ")))
  (is (= "" (crypto/normalize-email nil)))
  (is (true? (crypto/email-ok? "user@example.com")))
  (is (false? (crypto/email-ok? "nope")))
  (is (false? (crypto/email-ok? "")))
  (is (false? (crypto/email-ok? "a@b"))))

(deftest hash-check-test
  (binding [crypto/*cost* 4]
    (let [h (crypto/hash-secret "secret12")]
      (is (true? (crypto/check-secret "secret12" h)))
      (is (false? (crypto/check-secret "other" h)))
      (is (false? (crypto/check-secret "secret12" nil)))
      (is (false? (crypto/check-secret nil h)))))
  (is (= 12 crypto/bcrypt-cost)))

(deftest random-test
  (binding [crypto/*random* (Random. 1)]
    (is (= 16 (count (crypto/random-bytes 16))))
    (is (re-matches #"[0-9a-f]+" (crypto/random-hex 8)))
    (is (= 64 (count (crypto/session-id))))
    (is (= 48 (count (crypto/reset-token))))
    (let [pw (crypto/initial-password)]
      (is (= 12 (count pw)))
      (is (every? (set crypto/password-chars) pw))
      (is (not (some #{\0 \O \1 \l \I} pw)))))
  (is (= 12 (count (crypto/initial-password)))))
