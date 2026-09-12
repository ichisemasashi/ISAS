(ns isas.crypto
  (:require [buddy.hashers :as hashers]
            [clojure.string :as str])
  (:import [java.security SecureRandom]
           [java.util HexFormat]))

(def bcrypt-cost 12)

(def ^:dynamic *cost* nil)

(def password-chars
  (vec "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"))

(def ^:dynamic *random* nil)

(defn- rng []
  (or *random* (SecureRandom.)))

(defn- cost []
  (or *cost* bcrypt-cost))

(defn hash-secret [s]
  (hashers/derive s {:alg :bcrypt+sha512 :cost (cost)}))

(defn check-secret [s hashed]
  (boolean (and (string? hashed)
                (string? s)
                (hashers/check s hashed))))

(defn random-bytes [n]
  (let [buf (byte-array n)]
    (.nextBytes (rng) buf)
    buf))

(defn random-hex [n-bytes]
  (.formatHex (HexFormat/of) (random-bytes n-bytes)))

(defn session-id []
  (random-hex 32))

(defn reset-token []
  (random-hex 24))

(defn initial-password []
  (let [r (rng)
        n (count password-chars)]
    (apply str (repeatedly 12 #(nth password-chars (.nextInt r n))))))

(defn normalize-email [email]
  (-> (or email "")
      str
      str/trim
      str/lower-case))

(defn email-ok? [email]
  (let [e (normalize-email email)]
    (boolean (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" e))))
