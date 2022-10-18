;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.services
  "A helpers and macros for define rpc like registry based services."
  (:refer-clojure :exclude [defmethod])
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]))

;; A utilty wrapper object for wrap service responses that does not
;; implements the IObj interface that make possible attach metadata to
;; it.

(deftype MetadataWrapper [obj ^:unsynchronized-mutable metadata]
  clojure.lang.IDeref
  (deref [_] obj)

  clojure.lang.IObj
  (withMeta [_ meta]
    (MetadataWrapper. obj meta))

  (meta [_] metadata))

(defn wrap
  "Conditionally wrap a value into MetadataWrapper instance. If the
  object already implements IObj interface it will be returned as is."
  ([] (wrap nil))
  ([o]
   (if (instance? clojure.lang.IObj o)
     o
     (MetadataWrapper. o {}))))

(defn wrapped?
  [o]
  (instance? MetadataWrapper o))

(defmacro defmethod
  [sname & body]
  (let [[docs body]  (if (string? (first body))
                       [(first body) (rest body)]
                       [nil body])
        [mdata body] (if (map? (first body))
                       [(first body) (rest body)]
                       [nil body])

        [args body]  (if (vector? (first body))
                       [(first body) (rest body)]
                       [nil body])]
    (when-not args
      (throw (IllegalArgumentException. "Missing arguments on `defmethod` macro.")))

    (let [mdata (assoc mdata
                       ::docstring (some-> docs str/<<-)
                       ::spec sname
                       ::name (name sname))

          sym   (symbol (str "sm$" (name sname)))]
      `(do
         (def ~sym (fn ~args ~@body))
         (reset-meta! (var ~sym) ~mdata)))))

(def nsym-xf
  (comp
   (d/domap require)
   (map find-ns)
   (mapcat (fn [ns]
             (->> (ns-publics ns)
                  (map second)
                  (filter #(::spec (meta %)))
                  (map (fn [fvar]
                         (with-meta (deref fvar)
                           (-> (meta fvar)
                               (assoc :ns (-> ns ns-name str)))))))))))

(defn scan-ns
  [& nsyms]
  (sequence nsym-xf nsyms))
