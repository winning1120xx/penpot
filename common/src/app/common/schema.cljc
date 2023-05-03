;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema
  (:refer-clojure :exclude [deref merge])
  #?(:cljs (:require-macros [app.common.schema :refer [ignoring]]))
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.schema.registry :as sr]
   [app.common.uuid :as uuid]
   [clojure.test.check.generators :as tgen]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.error :as me]
   [malli.dev.pretty :as mdp]
   [malli.generator :as mg]
   [malli.registry :as mr]
   [malli.transform :as mt]
   [malli.util :as mu]))

(defn validate
  [s value]
  (m/validate s value {:registry sr/default-registry}))

(defn explain
  [s value]
  (m/explain s value {:registry sr/default-registry}))

(defn explain-data
  [s value]
  (mu/explain-data s value {:registry sr/default-registry}))

(defn schema?
  [o]
  (m/schema? o))

(defn schema
  [s]
  (m/schema s {:registry sr/default-registry}))

(defn humanize
  [exp]
  (me/humanize exp))

(defn generate
  ([s]
   (mg/generate (schema s)))
  ([s o]
   (mg/generate (schema s) o)))

(defn form
  [s]
  (m/form s))

(defn merge
  [& items]
  (apply mu/merge (map schema items)))

(defn ref?
  [s]
  (m/-ref-schema? s))

(defn deref
  [s]
  (m/deref s))

(defn error-values
  [exp]
  (malli.error/error-value exp {:malli.error/mask-valid-values '...}))

(def default-transformer
  (let [default-decoder
        {:compile (fn [s _registry]
                    (let [props (m/type-properties s)]
                      (or (::oapi/decode props)
                          (::decode props))))}

        default-encoder
        {:compile (fn [s _]
                    (let [props (m/type-properties s)]
                      (or (::oapi/encode props)
                          (::encode props))))}

        coders {:vector mt/-sequential-or-set->vector
                :sequential mt/-sequential-or-set->seq
                :set mt/-sequential->set
                :tuple mt/-sequential->vector}]

    (mt/transformer
     {:name :penpot
      :default-decoder default-decoder
      :default-encoder default-encoder}
     {:name :string
      :decoders (mt/-string-decoders)
      :encoders (mt/-string-encoders)}
     {:name :collections
      :decoders coders
      :encoders coders}

     )))

(defn validator
  [s]
  (-> s schema m/validator))

(defn explainer
  [s]
  (-> s schema m/explainer))

(defn lazy-validator
  [s]
  (let [vfn (delay (validator s))]
    (fn [v] (@vfn v))))

(defn lazy-explainer
  [s]
  (let [vfn (delay (explainer s))]
    (fn [v] (@vfn v))))

(defn encode
  ([s val transformer]
   (m/encode s val {:registry sr/default-registry} transformer))
  ([s val options transformer]
   (m/encode s val options transformer)))

(defn decode
  ([s val transformer]
   (m/decode s val {:registry sr/default-registry} transformer))
  ([s val options transformer]
   (m/decode s val options transformer)))

(defn decoder
  ([s transformer]
   (m/decoder s  {:registry sr/default-registry} transformer))
  ([s options transformer]
   (m/decoder s options transformer)))

(defn humanize-data
  [explain-data]
  (let [errors  (humanize explain-data)
        schema  (-> explain-data :schema m/form)
        value   (-> explain-data error-values)
        value   (if (map? value)
                  (d/without-qualified value)
                  value)]
    (array-map
     :schema schema
     :value value
     :errors errors)))

(defn pretty-explain
  [s d]
  (mdp/explain (schema s) d))

(defmacro ignoring
  [expr]
  (if (:ns &env)
    `(try ~expr (catch :default e# nil))
    `(try ~expr (catch Throwable e# nil))))

(defn simple-schema
  [& {:keys [pred] :as options}]
  (cond-> options
    (contains? options :type-properties)
    (update :type-properties (fn [props]
                               (cond-> props
                                 (contains? props :decode/string)
                                 (update :decode/string (fn [decode-fn]
                                                          (fn [s]
                                                            (if (pred s)
                                                              s
                                                              (or (ignoring (decode-fn s)) s)))))
                                 (contains? props ::decode)
                                 (update ::decode (fn [decode-fn]
                                                    (fn [s]
                                                      (if (pred s)
                                                        s
                                                        (or (ignoring (decode-fn s)) s))))))))
    :always
    (m/-simple-schema)))

(defn lookup
  "Lookups schema from registry."
  ([s] (lookup sr/default-registry s))
  ([registry s] (schema (mr/schema registry s))))

(defmacro assert!
  [& [s value hint]]
  (when *assert*
    `(let [s# (schema ~s)
           v# ~value
           h# ~(or hint (str "schema assert: " (pr-str s)))]
       (if (m/validate s# v#)
         v#
         (let [e# (explain s# v#)
               d# {:type :assertion
                   :code :data-validation
                   :hint h#
                   ::explain e#}]
           (throw (ex-info h# d#)))))))

;; (defmacro verify!
;;   "A variant of `assert!` macro that evaluates always, independently
;;   of the *assert* value."
;;   [& params]
;;   (binding [*assert* true]
;;     `(assert! ~@params)))

(defn pred-fn
  [s]
  (let [s    (schema s)
        v-fn (lazy-validator s)
        e-fn (lazy-explainer s)]
    (fn [v]
      (let [result (v-fn v)]
        (when (and (not result) (true? dm/*assert-context*))
          (let [hint (str "schema assert: " (pr-str (m/form s)))
                exp  (e-fn v)]
            (throw (ex-info hint {:type :assertion
                                  :code :data-validation
                                  :hint hint
                                  ::explain exp}))))
         result))))

(defn assert-fn
  [s]
  (let [f (pred-fn s)]
    (fn [v]
      (dm/assert! (f v)))))

(defmacro verify-fn
  [s]
  (let [f (pred-fn s)]
    (fn [v]
      (dm/verify! (f v)))))

(defn register! [type s]
  (let [s (if (map? s) (simple-schema s) s)]
    (swap! sr/registry assoc type s)))

(defn def! [type s]
  (register! type s)
  nil)

;; --- GENERATORS

(defn gen-set-from-choices
  [choices]
  (->> tgen/nat
       (tgen/fmap (fn [i]
                    (into #{}
                          (map (fn [_] (rand-nth choices)))
                          (range i))))))


;; --- BUILTIN SCHEMAS

(def! :merge (mu/-merge))
(def! :union (mu/-union))

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def! ::uuid
  {:type ::uuid
   :pred uuid?
   :type-properties
   {:title "uuid"
    :description "UUID formatted string"
    :error/message "should be an uuid"
    :gen/gen (sg/uuid)
    ::oapi/type "string"
    ::oapi/format "uuid"
    ::oapi/decode #(uuid/uuid (re-matches uuid-rx %))}})

(def email-re #"[a-zA-Z0-9_.+-\\\\]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(defn parse-email
  [s]
  (some->> s (re-seq email-re) first))

;; FIXME: add proper email generator
(def! ::email
  {:type ::email
   :pred (fn [s]
           (and (string? s) (re-seq email-re s)))
   :type-properties
   {:title "email"
    :description "string with valid email address"
    :error/message "expected valid email"
    :gen/gen (-> :string sg/generator)
    ::oapi/type "string"
    ::oapi/format "email"
    ::oapi/decode parse-email}})

(def non-empty-strings-xf
  (comp
   (filter string?)
   (remove str/empty?)
   (remove str/blank?)))

(def! ::set-of-strings
  {:type ::set-of-strings
   :pred #(and (set? %) (every? string? %))
   :type-properties
   {:title "set[string]"
    :description "Set of Strings"
    :error/message "should be an set of strings"
    :gen/gen (-> :string sg/generator sg/set)
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string"}
    ::oapi/unique-items true
    ::decode (fn [v]
               (into #{} non-empty-strings-xf (str/split v #"[\s,]+")))}})


(def! ::set-of-emails
  {:type ::set-of-strings
   :pred #(and (set? %) (every? string? %))
   :type-properties
   {:title "set[email]"
    :description "Set of Emails"
    :error/message "should be an set of emails"
    :gen/gen (-> ::email sg/generator sg/set)
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string" :format "email"}
    ::oapi/unique-items true
    ::decode (fn [v]
               (into #{} (map parse-email) (str/split v #"[\s,]+")))}})

(def! ::one-of
  {:type ::one-of
   :min 1
   :max 1
   :compile (fn [props children options]
              (let [options (into #{} (last children))
                    format  (:format props "keyword")]
                {:pred #(contains? options %)
                 :type-properties
                 {:title "one-of"
                  :description "One of the Set"
                  :gen/gen (sg/elements options)
                  ::oapi/type "string"
                  ::oapi/format (:format props "keyword")
                  ::decode (if (= format "keyword")
                             keyword
                             identity)}}))})

(def max-safe-int (int 1e6))
(def min-safe-int (int -1e6))

(def! ::safe-int
  {:type ::safe-int
   :pred #(and (int? %) (>= max-safe-int %) (>= % min-safe-int))
   :type-properties
   {:title "int"
    :description "Safe Integer"
    :error/message "expected to be int in safe range"
    :gen/gen (sg/small-int)
    ::oapi/type "integer"
    ::oapi/format "int64"
    ::oapi/decode parse-long}})

(def! ::safe-number
  {:type ::safe-number
   :pred #(and (number? %) (>= max-safe-int %) (>= % min-safe-int))
   :type-properties
   {:title "number"
    :description "Safe Number"
    :error/message "expected to be number in safe range"
    :gen/gen (sg/one-of (sg/small-int)
                        (sg/small-double))
    ::oapi/type "number"
    ::oapi/format "double"
    ::oapi/decode parse-double}})

(def! ::safe-double
  {:type ::safe-double
   :pred #(and (double? %) (>= max-safe-int %) (>= % min-safe-int))
   :type-properties
   {:title "number"
    :description "Safe Number"
    :error/message "expected to be number in safe range"
    :gen/gen (sg/small-double)
    ::oapi/type "number"
    ::oapi/format "double"
    ::oapi/decode parse-double}})

(def! ::contains-any
  {:type ::contains-any
   :min 1
   :max 1
   :compile (fn [props children options]
              (let [choices (last children)
                    pred    (if (:strict props)
                              #(some (fn [prop]
                                       (some? (get % prop)))
                                     choices)
                              #(some (fn [prop]
                                       (contains? % prop))
                                     choices))]
                {:pred pred
                 :type-properties
                 {:title "contains"
                  :description "contains predicate"}}))})

(def! ::inst
  {:type ::inst
   :pred inst?
   :type-properties
   {:title "inst"
    :description "Satisfies Inst protocol"
    :error/message "expected to be number in safe range"
    :gen/gen (sg/small-int)
    ::oapi/type "number"
    ::oapi/format "int64"}})

;; ---- PREDICATES

(def safe-int?
  (pred-fn ::safe-int))

(def set-of-strings?
  (pred-fn ::set-of-strings))

(def set-of-emails?
  (pred-fn ::set-of-emails))

(def email?
  (pred-fn ::email))


