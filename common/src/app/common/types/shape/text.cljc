;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.text
  (:require
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.shape :as-alias shape]
   [app.common.types.shape.text.position-data :as-alias position-data]
   [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SPEC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::type #{"root" "paragraph-set" "paragraph"})
(s/def ::text string?)
(s/def ::key string?)
(s/def ::fill-color string?)
(s/def ::fill-opacity ::us/safe-number)
(s/def ::fill-color-gradient (s/nilable ::ctc/gradient))

(s/def ::content
  (s/nilable
   (s/or :text-container
         (s/keys :req-un [::type]
                 :opt-un [::key
                          ::children])
         :text-content
         (s/keys :req-un [::text]))))

(s/def ::children
  (s/coll-of ::content
             :kind vector?
             :min-count 1))

(s/def ::position-data
  (s/coll-of ::position-data-element
             :kind vector?
             :min-count 1))

(s/def ::position-data-element
  (s/keys :req-un [::position-data/x
                   ::position-data/y
                   ::position-data/width
                   ::position-data/height]
          :opt-un [::position-data/fill-color
                   ::position-data/fill-opacity
                   ::position-data/font-family
                   ::position-data/font-size
                   ::position-data/font-style
                   ::position-data/font-weight
                   ::position-data/rtl
                   ::position-data/text
                   ::position-data/text-decoration
                   ::position-data/text-transform]))

(s/def ::position-data/x ::us/safe-number)
(s/def ::position-data/y ::us/safe-number)
(s/def ::position-data/width ::us/safe-number)
(s/def ::position-data/height ::us/safe-number)

(s/def ::position-data/fill-color ::fill-color)
(s/def ::position-data/fill-opacity ::fill-opacity)
(s/def ::position-data/fill-color-gradient ::fill-color-gradient)

(s/def ::position-data/font-family string?)
(s/def ::position-data/font-size string?)
(s/def ::position-data/font-style string?)
(s/def ::position-data/font-weight string?)
(s/def ::position-data/rtl boolean?)
(s/def ::position-data/text string?)
(s/def ::position-data/text-decoration string?)
(s/def ::position-data/text-transform string?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def node-types #{"root" "paragraph-set" "paragraph"})

(sm/def! ::content
  [:map
   [:type [:= "root"]]
   [:key {:optional true} :string]
   [:children
    [:vector {:min 1 :gen/max 2 :gen/min 1}
     [:map
      [:type [:= "paragraph-set"]]
      [:key {:optional true} :string]
      [:children
       [:vector {:min 1 :gen/max 2 :gen/min 1}
        [:map
         [:type [:= "paragraph"]]
         [:key {:optional true} :string]
         [:fills {:optional true}
          [:vector {:gen/max 2} ::shape/fill]]
         [:font-family :string]
         [:font-size :string]
         [:font-style :string]
         [:font-weight  :string]
         [:direction :string]
         [:text-decoration :string]
         [:text-transform :string]
         [:typography-ref-id [:maybe ::sm/uuid]]
         [:typography-ref-file [:maybe ::sm/uuid]]
         [:children
          [:vector {:min 1 :gen/max 2 :gen/min 1}
           [:map
            [:text :string]
            [:key :string]
            [:fills [:vector {:gen/max 2} ::shape/fill]]
            [:font-family :string]
            [:font-size :string]
            [:font-style :string]
            [:font-weight  :string]
            [:direction :string]
            [:text-decoration :string]
            [:text-transform :string]
            [:typography-ref-id [:maybe ::sm/uuid]]
            [:typography-ref-file [:maybe ::sm/uuid]]]]]]]]]]]])



(sm/def! ::position-data
  [:vector {:min 1 :gen/max 2}
   [:map
    [:x ::sm/safe-number]
    [:y ::sm/safe-number]
    [:width ::sm/safe-number]
    [:height ::sm/safe-number]
    [:fills [:vector {:gen/max 2} ::shape/fill]]
    [:font-family {:optional true} :string]
    [:font-size {:optional true} :string]
    [:font-style {:optional true} :string]
    [:font-weight {:optional true} :string]
    [:rtl {:optional true} :boolean]
    [:text {:optional true} :string]
    [:text-decoration {:optional true} :string]
    [:text-transform {:optional true} :string]]])

