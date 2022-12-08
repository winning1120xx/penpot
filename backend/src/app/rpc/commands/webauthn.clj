;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.webauthn
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.emails :as eml]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.rpc.climit :as climit]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.mutations.teams :as teams]
   [app.rpc.queries.profile :as profile]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [buddy.hashers :as hashers]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str])
  (:import
   com.yubico.webauthn.CredentialRepository
   com.yubico.webauthn.RegistrationResult
   com.yubico.webauthn.RelyingParty
   com.yubico.webauthn.data.ByteArray
   com.yubico.webauthn.data.PublicKeyCredential
   com.yubico.webauthn.data.PublicKeyCredentialDescriptor
   com.yubico.webauthn.data.RelyingPartyIdentity

   ))

(def repository
  (let [storage (atom {})]
    (reify CredentialRepositoru
      (getCredentialIdsForUsername [_ uname])
      (getUserHandleForUsername [_ uname])
      (getUsernameForUserHandle [_ uhandle])
      (lookup [_ cid uhandle])
      (lookupAll [_ cid]))))

(def rparty
  (delay
    (let [identity (.. (RelyingPartyIdentity/builder)
                       (id "penpot.dev")
                       (name "Penpot Application")
                       (build))]
      (.. (RelyingParty/builder)
          (identity identity)
          (credentialRepository repository)
          (build)))))

(defn start-registration-request!
  [profile]
  (let [uhandle  (ByteArray. (uuid/get-bytes (:id profile)))
        identity (.. (UserIdentity/builder)
                     (name (:email profile))
                     (displayName (:fullname profile))
                     (id uhandle)
                     (build))
        options  (.. (StartRegistrationOptions/builder)
                     (user identity)
                     (build))
        request  (.startRegistration @rparty options)]
    {:client (.toCredentialsCreateJson request)
     :server (.toJson request)}))

(def storate
  (atom {})

(def registration-cache
  (atom {})

(s/def ::start-webauthn-registration
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::start-webauthn-registration
  "Performs authentication using penpot password."
  {::climit/queue :auth}
  [{:keys [::db/pool]} {:keys [profile-id]}]
  (let [profile (db/get pool :profile {:id profile-id})
        request (start-registration-request! profile)]
    (swap! registration-cache assoc profile-id (:server request))
    {:request (:client request)}))

(s/def ::pubkey ::us/not-empty-string)
(s/def ::finish-webauthn-registration
  (s/def :req-un [::pubkey]))

(sv/defmethod ::finish-webauthn-registration
  "Performs authentication using penpot password."
  {::climit/queue :auth}
  [{:keys [::db/pool]} {:keys [profile-id pubkey]}]
  (let [profile (db/get pool :profile {:id profile-id})
        opts    (.. (FinishRegistrationOptions/builder)
                    (request (get @registration-cache profile-id))
                    (response (PublicKeyCredential/parseRegistrationResponseJson pubkey))
                    (build))
        result  (.finishRegistration @rparty opts)]

    (swap! storate update (:email profile)
           (fn [storage]
             (let [storage (or storage #{})
                   keyid   (.getKeyId result)]
               (conj storage {:key/id (.getId keyid)
                              :key/transports (.orElse (.getTransports keyid) nil)
                              :key/type (.getId (.getType keyid))
                              :key/data (.getPublicKeyCose result)
                              :signature/count (.getSignatureCount result)}))))
    nil))
