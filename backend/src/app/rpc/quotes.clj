;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.quotes
  "Penpot resource usage quotes."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.config :as cf]
   [clojure.spec.alpha :as s]))

(defmulti check-quote ::id)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::conn ::db/conn-or-pool)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::incr (s/and int? pos?))

(s/def ::quote
  (s/keys :req [::id ::profile-id]
          :opt [::conn
                ::team-id
                ::project-id
                ::file-id
                ::incr]))

(def ^:private enabled (volatile! true))

(defn enable!
  "Enable quotes checking at runtime (from server REPL)."
  []
  (vswap! enabled (constantly true)))

(defn disable!
  "Disable quotes checking at runtime (from server REPL)."
  []
  (vswap! enabled (constantly false)))

(defn check-quote!
  [conn quote]
  (us/assert! ::db/conn-or-pool conn)
  (us/assert! ::quote quote)
  (when (contains? cf/flags :quotes)
    (when @enabled
      (check-quote (assoc quote ::conn conn)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: TEAMS-PER-PROFILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-teams-per-profile-quote
  "select id, quote from usage_quote
    where obj = 'team'
      and (profile_id is null or profile_id = ?);")

(def ^:private sql:get-teams-per-profile
  "select count(*) as total
     from team_profile_rel
    where profile_id = ?")

(defmethod check-quote ::teams-per-profile
  [{:keys [::conn ::id ::profile-id] :as quote}]
  (let [default (cf/get :quotes-teams-per-profile Integer/MAX_VALUE)
        quote   (->> (db/exec! conn [sql:get-teams-per-profile-quote profile-id])
                     (map :quote)
                     (reduce max default))
        total   (->> (db/exec! conn [sql:get-teams-per-profile profile-id]) first :total)]
    (when (> total quote)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :object :team
                :quote quote
                :total total))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROJECTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-projects-per-team-quote
  "select id, quote from usage_quote
    where obj = 'project'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-projects-per-team
  "select count(*) as total
     from project as p
     where p.team_id = ?")

(defmethod check-quote ::projects-per-team
  [{:keys [::conn ::profile-id ::team-id] :as quote}]
  (let [default (cf/get :quotes-projects-per-team Integer/MAX_VALUE)
        quote   (->> (db/exec! conn [sql:get-projects-per-team-quote
                                     team-id profile-id team-id])
                     (map :quote)
                     (reduce max default))
        total  (->> (db/exec! conn [sql:get-projects-per-team team-id]) first :total)]
    (when (> total quote)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :object :project
                :quote quote
                :total total))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: INVITATIONS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-invitations-per-team-quote
  "select id, quote from usage_quote
    where obj = 'team-invitation'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-invitations-per-team
  "select count(*) as total
     from team_invitation
    where team_id = ?")

(defmethod check-quote ::invitations-per-team
  [{:keys [::conn ::profile-id ::team-id] :as quote}]
  (let [default (cf/get :quotes-invitations-per-team Integer/MAX_VALUE)
        quote   (->> (db/exec! conn [sql:get-invitations-per-team-quote
                                     team-id profile-id team-id])
                     (map :quote)
                     (reduce max default))
        total  (->> (db/exec! conn [sql:get-invitations-per-team team-id]) first :total)]
    (when (> total quote)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :object :team-invitation
                :quote quote
                :total total))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROFILES-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-profiles-per-team-quote
  "select id, quote from usage_quote
    where obj = 'team-member'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-profiles-per-team
  "select count(*) as total
     from team_profile_rel
    where team_id = ?")

(defmethod check-quote ::profiles-per-team
  [{:keys [::conn ::profile-id ::team-id] :as quote}]
  (let [default (cf/get :quotes-profiles-per-team Integer/MAX_VALUE)
        quote   (->> (db/exec! conn [sql:get-profiles-per-team-quote
                                     team-id profile-id team-id])
                     (map :quote)
                     (reduce max default))
        total  (->> (db/exec! conn [sql:get-profiles-per-team team-id]) first :total)]
    (when (> total quote)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :object :team-member
                :quote quote
                :total total))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FILES-PER-PROJECT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-per-project-quote
  "select id, quote from usage_quote
    where obj = 'file'
      and ((profile_id is null and project_id = ?) or
           (profile_id = ? and project_id = ?));")

(def ^:private sql:get-files-per-project
  "select count(*) as total
     from file as f
     join project as p on (f.project_id=p.id)
    where p.project_id = ?")

(defmethod check-quote ::files-per-project
  [{:keys [::conn ::profile-id ::project-id] :as quote}]
  (let [default (cf/get :quotes-files-per-project Integer/MAX_VALUE)
        quote   (->> (db/exec! conn [sql:get-files-per-project-quote
                                     project-id profile-id project-id])
                     (map :quote)
                     (reduce max default))
        total   (->> (db/exec! conn [sql:get-files-per-project project-id]) first :total)]
    (when (> total quote)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :object :file
                :quote quote
                :total total))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FILES-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-per-team-quote
  "select id, quote from usage_quote
    where obj = 'file'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-files-per-team
  "select count(*) as total
     from file as f
     join project as p on (f.project_id=p.id)
    where p.team_id = ?")

(defmethod check-quote ::files-per-team
  [{:keys [::conn ::profile-id ::team-id] :as quote}]
  (let [default (cf/get :quotes-files-per-team Integer/MAX_VALUE)
        quote   (->> (db/exec! conn [sql:get-files-per-team-quote
                                     team-id profile-id team-id])
                     (map :quote)
                     (reduce max default))
        total   (->> (db/exec! conn [sql:get-files-per-team team-id]) first :total)]
    (when (> total quote)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :object :file
                :quote quote
                :total total))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: DEFAULT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod check-quote :default
  [{:keys [::id]}]
  (ex/raise :type :internal
            :code :quote-not-defined
            :quote id
            :hint "backend using a quote identifier not defined"))
