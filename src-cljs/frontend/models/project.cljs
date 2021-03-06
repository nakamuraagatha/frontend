(ns frontend.models.project
  (:require [clojure.string :refer [lower-case split join]]
            [frontend.utils :as utils :include-macros true]
            [goog.string :as gstring]
            [frontend.models.plan :as plan-model]
            [frontend.config :as config]
            [frontend.routes :as routes]
            [frontend.utils.vcs-url :as vcs-url]))

(defn project-name [project]
  (let [username (:username project)
        reponame (:reponame project)]
    (if (and username reponame)
      (str username "/" reponame)
      (->> (split (:vcs_url project) #"/")
           (take-last 2)
           (join "/")))))

(defn vcs-type [project]
  (if-let [vcs-type (:vcs_type project)]
    vcs-type
    (vcs-url/vcs-type (:vcs_url project))))

(defn settings-path [project]
  (str "/" (-> project vcs-type routes/->short-vcs) "/" (vcs-url/project-name project) "/edit"))

(defn repo-name [project]
  (:reponame project))

(defn org-name [project]
  (:username project))

(defn default-branch? [branch-name project]
  (= (name branch-name) (:default_branch project)))

(defn- personal-branch-helper [project login branch pushers]
  (or (default-branch? branch project)
      (some #{login} pushers)))

(defn personal-branch? [login branch]
  (personal-branch-helper (:project branch) login (:identifier branch) (:pusher_logins branch)))

(defn branch-builds [project branch-name-kw]
  (let [build-data (get-in project [:branches branch-name-kw])]
    (sort-by :build_num (concat (:running_builds build-data)
                                (:recent_builds build-data)))))

(defn master-builds
  "Returns branch builds for the project's default branch (usually master)"
  [project]
  (branch-builds project (keyword (:default_branch project))))

(def hipchat-keys [:hipchat_room :hipchat_api_token :hipchat_notify :hipchat_notify_prefs])
(def slack-keys [:slack_channel_override :slack_channel :slack_subdomain :slack_api_token :slack_notify_prefs :slack_webhook_url])
(def campfire-keys [:campfire_room :campfire_token :campfire_subdomain :campfire_notify_prefs])
(def flowdock-keys [:flowdock_api_token])
(def irc-keys [:irc_server :irc_channel :irc_keyword :irc_username :irc_password :irc_notify_prefs])

(def notification-keys (concat hipchat-keys slack-keys campfire-keys flowdock-keys irc-keys))

(defn notification-settings [project]
  (select-keys project notification-keys))

(defn last-master-build
  "Gets the last finished master build on the branch"
  [project]
  (first (get-in project [:branches (keyword (:default_branch project)) :recent_builds])))

(defn sidebar-sort [l, r]
  (let [l-last-build (last-master-build l)
        r-last-build (last-master-build r)]
    (cond (and l-last-build r-last-build)
          (compare (:build_num r-last-build)
                   (:build_num l-last-build))

          l-last-build -1
          r-last-build 1
          :else (compare (lower-case (:vcs_url l)) (lower-case (:vcs_url r))))))

(defn most-recent-activity-time [{:as branch-data :keys [running_builds recent_builds]}]
  (let [running (:added-at (first running_builds))
        complete (:added-at (first recent_builds))]
    (or running complete)))

(defn project->project-per-branch [project]
  (map
   (fn [[branch branch-data]]
     (-> project
         (assoc :current-branch branch)
         (assoc :pusher_logins (:pusher_logins branch-data))
         (assoc :recent-activity-time (if-let [time (most-recent-activity-time branch-data)]
                                        (js/Date.parse time)
                                        :not-built))
         (update :branches select-keys [branch])))
   (:branches project)))

(defn recent-project-was-built? [recent-project]
  (not= :not-built (:recent-activity-time recent-project)))

(defn branches
  "Returns a collection of branches the project contains. Each branch will
  include its :identifier (the key it was listed under in the project) and
  its :project."
  [project]
  (for [[name-kw branch-data] (:branches project)]
    (merge branch-data
           {:identifier name-kw
            :project project})))

(defn sort-branches-by-recency [projects]
  (->> projects
       (mapcat branches)
       ;; Branches without activity do not appear in the Recent branch list.
       (filter most-recent-activity-time)
       (sort-by #(js/Date.parse (most-recent-activity-time %))
                ;; This inverts compare, yielding a reverse sort.
                (comp - compare))))


(defn personal-recent-project? [login recent-project]
  (personal-branch-helper recent-project
                          login
                          (:current-branch recent-project)
                          (:pusher_logins recent-project)))

(defn id [project]
  (:vcs_url project))

;; I was confused, on the backend, we now default to oss if
;; github-info shows it to be public and no one sets the flag
;; explicitly to false. But the read-api always delivers the frontend
;; a feature_flags oss set to true for those cases.
(defn oss? [project]
  (get-in project [:feature_flags :oss]))

(defn osx? [project]
  (get-in project [:feature_flags :osx]))

(defn usable-containers [plan project]
  (+ (plan-model/linux-containers plan)
     (if (oss? project) plan-model/oss-containers 0)))

(defn buildable-parallelism [plan project]
  (min (plan-model/max-parallelism plan)
       (usable-containers plan project)))

(defn has-scope? [project scope]
  (let [scope (keyword scope)]
    (boolean (some->> project :scopes (map keyword) set scope))))

(defn can-trigger-builds? [project]
  (has-scope? project :trigger-builds))

(defn can-write-settings? [project]
  (has-scope? project :write-settings))

(defn can-read-settings? [project]
  (has-scope? project :read-settings))

(defn feature-flags [project]
  (:feature_flags project))

(defn feature-enabled? [project feature]
  (get-in project [:feature_flags feature]))

(defn- show-premium-content? [project plan]
  (or (config/enterprise?)
      (:oss project)
      (pos? (plan-model/paid-linux-containers plan))
      (plan-model/in-trial? plan)
      (plan-model/osx? plan)))

(defn show-build-timing? [project plan]
  (show-premium-content? project plan))

(defn show-insights? [plans project]
  (let [org-name (-> project
                     (:vcs_url)
                     (vcs-url/org-name))
        org-best-plan (->> plans
                           (filter #(-> %
                                        :org_name
                                        (= org-name)))
                           (first)
                           (:plans)
                           (apply max-key plan-model/paid-linux-containers))]
    (show-premium-content? project org-best-plan)))

(defn show-upsell? [project plan]
  (not (show-premium-content? project plan)))

(defn parallel-available?
  "Are parallel builds available for this project type?"
  [project]
  (not (osx? project)))

(defn parallelism [project]
  (:parallel project))
