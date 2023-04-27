;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.shadow
  (:require
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.shape.shadow.color :as-alias shadow-color]
   [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SPECS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;; SHADOW EFFECT

(s/def ::id (s/nilable uuid?))
(s/def ::style #{:drop-shadow :inner-shadow})
(s/def ::offset-x ::us/safe-number)
(s/def ::offset-y ::us/safe-number)
(s/def ::blur ::us/safe-number)
(s/def ::spread ::us/safe-number)
(s/def ::hidden boolean?)

(s/def ::color string?)
(s/def ::opacity ::us/safe-number)
(s/def ::gradient (s/nilable ::ctc/gradient))
(s/def ::file-id (s/nilable uuid?))
(s/def ::ref-id (s/nilable uuid?))

(s/def ::shadow-color/color
  (s/keys :opt-un [::color
                   ::opacity
                   ::gradient
                   ::file-id
                   ::id]))

(s/def ::shadow-props
  (s/keys :req-un [::id
                   ::style
                   ::shadow-color/color
                   ::offset-x
                   ::offset-y
                   ::blur
                   ::spread
                   ::hidden]))

(s/def ::shadow
  (s/coll-of ::shadow-props :kind vector?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def styles #{:drop-shadow :inner-shadow})

(sm/def! ::shadow
  [:map {:title "Shadow"}
   [:id [:maybe ::sm/uuid]]
   [:style [::sm/one-of styles]]
   [:offset-x ::sm/safe-number]
   [:offset-y ::sm/safe-number]
   [:blur ::sm/safe-number]
   [:spread ::sm/safe-number]
   [:hidden :boolean]
    ;;FIXME: reuse color?
   [:color
    [:map
     [:color {:optional true} :string]
     [:opacity {:optional true} ::sm/safe-number]
     [:gradient {:optional true} [:maybe ::ctc/gradient]]
     [:file-id {:optional true} [:maybe ::sm/uuid]]
     [:id {:optional true} [:maybe ::sm/uuid]]]]])
