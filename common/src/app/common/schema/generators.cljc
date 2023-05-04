;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.generators
  (:refer-clojure :exclude [set subseq uuid])
  (:require
   [app.common.schema.registry :as sr]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [clojure.test.check.generators :as tgen]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.generator :as mg]
   [malli.util :as mu]))

(defn sample
  ([g]
   (mg/sample g {:registry sr/default-registry}))
  ([g opts]
   (mg/sample g (assoc opts :registry sr/default-registry))))

(defn generate
  ([g]
   (mg/generate g {:registry sr/default-registry}))
  ([g opts]
   (mg/generate g (assoc opts :registry sr/default-registry))))

(defn generator
  ([s]
   (mg/generator s {:registry sr/default-registry}))
  ([s opts]
   (mg/generator s (assoc opts :registry sr/default-registry))))

(defn small-double
  [& {:keys [min max] :or {min -100 max 100}}]
  (tgen/double* {:min min, :max max, :infinite? false, :NaN? false}))

(defn small-int
  [& {:keys [min max] :or {min -100 max 100}}]
  (tgen/large-integer* {:min min, :max max}))

(defn word-string
  []
  (->> (tgen/such-that #(re-matches #"\w+" %)
                       tgen/string-alphanumeric
                       50)
       (tgen/such-that (complement str/blank?))))

(defn uri
  []
  (tgen/let [scheme (tgen/elements ["http" "https"])
             domain (as-> (word-string) $
                      (tgen/such-that (fn [x] (> (count x) 5)) $ 100)
                      (tgen/fmap str/lower $))
             ext    (tgen/elements ["net" "com" "org" "app" "io"])]
    (u/uri (str scheme "://" domain "." ext))))

;; FIXME: revisit
(defn uuid
  []
  (->> tgen/small-integer
       (tgen/fmap (fn [_] (uuid/next)))))

(defn subseq
  "Given a collection, generates \"subsequences\" which are sequences
  of (not necessarily contiguous) elements from the original
  collection, in the same order. For collections of distinct elements
  this is effectively a subset generator, with an ordering guarantee."
  ([elements]
   (subseq [] elements))
  ([dest elements]
   (->> (apply tgen/tuple (repeat (count elements) tgen/boolean))
        (tgen/fmap (fn [bools]
                     (into dest
                           (comp
                            (filter first)
                            (map second))
                           (map list bools elements)))))))

(defn set
  [g]
  (tgen/set g))

(defn elements
  [s]
  (tgen/elements s))

(defn one-of
  [& gens]
  (tgen/one-of (into [] gens)))

(defn fmap
  [f g]
  (tgen/fmap f g))

(defn tuple
  [& opts]
  (apply tgen/tuple opts))

