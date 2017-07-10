(ns maria.trusted.trusted-actions
  (:require [re-db.d :as d]
            [maria.trusted.persistence.remote :as remote]
            [clojure.string :as string]
            [re-view-routing.core :as routing]
            [cljs.pprint :refer [pprint]]
            [cljs.core.match :refer-macros [match]]
            [maria.persistence.github :as github]
            [maria.frame-communication :as frame]
            [re-view-routing.core :as r]))



(defn navigate! [url opts]
  (if (string/starts-with? url "/")
    (routing/nav! url)
    (if (:popup? opts)
      (.open js/window url)
      (aset js/window "location" "href" url))))

(def editor-message-handler
  (memoize (fn [project-id]
             (fn [frame-id message]
               (match message
                      [:project/publish project-id project]
                      (let [owned? (= (str (d/get-in project-id [:persisted :owner :id]))
                                      (str (d/get-in :auth-secret [:provider-data 0 :uid])))]
                        (if owned?
                          (github/patch-gist project-id
                                             (github/project->gist project)
                                             (fn [{:keys [value error]}]
                                               (if value (d/transact! [[:db/add project-id :persisted value]])
                                                         (.error js/console "Error patching gist: " error))))
                          (throw (js/Error. "Cannot publish project owned by another user."))))

                      [:project/create project]
                      (github/create-gist (github/project->gist project) (fn [{{id :id :as project} :value
                                                                               error                :error}]
                                                                           (if project (do
                                                                                         (d/transact! [[:db/add id :persisted project]])
                                                                                         (frame/send frame-id [:project/clear-new!])
                                                                                         (r/nav! (str "/gist/" id)))
                                                                                       (.error js/console "Error creating gist: " error))))

                      [:project/fork project-id]
                      (github/fork-gist project-id (fn [{{new-id :id :as project} :value
                                                         error                    :error}]
                                                     (if project
                                                       (do
                                                         (frame/send frame-id [:db/copy-local project-id new-id])
                                                         (d/transact! [[:db/add new-id :persisted project]])
                                                         (r/nav! (str "/gist/" new-id)))
                                                       (.error js/console "Error creating gist: " error))))

                      [:auth/sign-in] (remote/sign-in :github)

                      [:auth/sign-out] (remote/sign-out)

                      [:window/navigate url opts] (navigate! url opts)
                      :else (prn "Unknown message: " message))))))