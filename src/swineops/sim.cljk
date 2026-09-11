(ns swineops.sim
  "Simple simulation/demo runner for the Swine-Farm Operations Coordinator
  actor. Used to validate that the actor flow compiles and basic
  proposal flow works. Mirrors `cattleops.sim` (cloud-itonami-isic-0141)."
  (:require [swineops.operation :as operation]
            [swineops.store :as store]))

(defn demo
  "Run a simple demo scenario: register a facility, propose a herd-record
  log, and check the disposition flow."
  []
  (let [;; Create store with a registered facility
        st (store/mem-store
            {:initial-facilities
             {"farm-001"
              {:id "farm-001"
               :name "Sunrise Swine Farm"
               :barn "Barn 3, Pen 12"
               :breed "landrace"}}})

        ;; Build actor
        actor (operation/build st)

        ;; Create a request to log a herd record
        request {:op :log-herd-record
                 :facility-id "farm-001"
                 :count 120
                 :health-status "healthy"}

        ;; Context with phase 0 (simulation)
        context {:actor-id "swine-ops-01"
                 :role :farm-operator
                 :phase :phase-0}]

    (println "=== Swine-Farm Operations Coordinator Demo ===")
    (println "Demo facility: farm-001 (Barn 3, Pen 12)")
    (println "Request: log-herd-record")
    (println "Phase: phase-0 (simulation)")
    (println "Expected: escalate (phase-0 forces human review of all commits)")
    (println)
    (let [result (actor request context)]
      (println "Result disposition:" (:disposition result))
      result)))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
)
