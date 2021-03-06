(ns restql.restql-test
  (:require [clojure.test :refer :all]
            [restql.parser.core :as parser]
            [restql.core.api.restql :as restql]
            [byte-streams :as bs]
            [restql.parser.json :as json]
            [stub-http.core :refer :all]
            [clojure.core.async :refer :all]
            [restql.test-util :refer [route-response route-request route-header]]))

(defn get-stub-body [request]
  (val (first (:body request))))

(defn hero-route []
  (route-response {:hi "I'm hero" :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]}))

(defn hero-with-bag-route []
  (route-response {:hi "I'm hero" :bag {:capacity 10}}))

(defn sidekick-route []
  (route-response {:hi "I'm sidekick"}))

(defn product-route [id]
  (route-response {:product (str id)}))

(defn heroes-route []
  {:status 200
   :content-type "application/json"
   :body (json/generate-string [{:hi "I'm hero" :villains ["1" "2"]} {:hi "I'm hero" :villains ["3" "4"]}])})

(defn villain-route [id]
  (route-response {:hi "I'm villain" :id (str id)}))

(defn execute-query
  ([base-url query]
   (execute-query base-url query {} {}))
  ([base-url query params]
   (execute-query base-url query params {}))
  ([base-url query params options]
   (restql/execute-query :mappings {:hero                (str base-url "/hero")
                                    :heroes              (str base-url "/heroes")
                                    :weapons             (str base-url "/weapons")
                                    :sidekick            (str base-url "/sidekick")
                                    :villain             (str base-url "/villain/:id")
                                    :weapon              (str base-url "/weapon/:id")
                                    :product             (str base-url "/product/:id")
                                    :product-price       (str base-url "/price/:productId")
                                    :product-description (str base-url "/description/:productId")
                                    :fail                "http://not.a.working.endpoint"}
                         :query query
                         :params params
                         :options options)))

(deftest simple-request
  (with-routes!
    {"/hero" (hero-route)}
    (let [result (execute-query uri "from hero")]
      (is (= 200 (get-in result [:hero :details :status])))
      (is (= {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]} (get-in result [:hero :result])))
      (is (= "I'm hero" (get-in result [:hero :result :hi]))))))

(deftest multiplexed-request

  (with-routes!
    {"/hero"      (route-response {:villains ["1" "2"]})
     "/villain/1" (villain-route "1")
     "/villain/2" (villain-route "2")}
    (let [result (execute-query uri "from hero\n from villain with id = hero.villains")]
      (is (= {:villains ["1" "2"]} (get-in result [:hero :result])))
      (is (= [{:hi "I'm villain", :id "1"} {:hi "I'm villain", :id "2"}] (get-in result [:villain :result])))))

  ; Test simple case with: single, list with one, list with two
  (with-routes!
    {"/hero" (hero-route)}
    (let [response (execute-query uri "from hero with name = $name" {:name "Doom"})
          details (get-in response [:hero :details])
          result (get-in response [:hero :result])]
      (is (= 200 (:status details)))
      (is (= {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]} result))))

  (with-routes!
    {"/hero" (hero-route)}
    (let [response (execute-query uri "from hero with name = $name" {:name ["Doom"]})
          details (get-in response [:hero :details])
          result (get-in response [:hero :result])]
      (is (= 200 (:status (first details))))
      (is (= [{:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]}] result))))

  (with-routes!
    {"/hero" (hero-route)}
    (let [response (execute-query uri "from hero with name = $name" {:name ["Doom" "Duke Nuken"]})
          details (get-in response [:hero :details])
          result (get-in response [:hero :result])]
      (is (= 200 (:status (first details))))
      (is (= 200 (:status (second details))))
      (is (= [{:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]}
              {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]}] result))))

  ;; Test simple case with list: single, list with one, list with two
  (with-routes!
    {"/hero"      (route-response {:villains ["1" "2"]})
     "/villain/1" (villain-route "1")
     "/villain/2" (villain-route "2")}
    (let [result (execute-query uri "from hero with id =1\nfrom villain with id = hero.villains")]
      (is (= 200 (:status (get-in result [:hero :details]))))
      (is (= 200 (:status (first   (get-in result [:villain :details])))))
      (is (= 200 (:status (second  (get-in result [:villain :details])))))
      (is (= {:villains ["1" "2"]} (get-in result [:hero :result])))
      (is (= [{:hi "I'm villain", :id "1"} {:hi "I'm villain", :id "2"}] (get-in result [:villain :result])))))

  (with-routes!
    {"/hero"      (route-response {:villains ["1" "2"]})
     "/villain/1" (villain-route "1")
     "/villain/2" (villain-route "2")}
    (let [result (execute-query uri "from hero with id =[1]\nfrom villain with id = hero.villains")]
      (is (= 200 (:status (first (get-in result [:hero :details])))))
      (is (= 200 (:status (first  (first (get-in result [:villain :details]))))))
      (is (= 200 (:status (second (first (get-in result [:villain :details]))))))
      (is (= [{:villains ["1" "2"]}] (get-in result [:hero :result])))
      (is (= [[{:hi "I'm villain", :id "1"} {:hi "I'm villain", :id "2"}]] (get-in result [:villain :result])))))

  (with-routes!
    {"/hero"      (route-response {:villains ["1" "2"]})
     "/villain/1" (villain-route "1")
     "/villain/2" (villain-route "2")}
    (let [result (execute-query uri "from hero with id =[1,2]\nfrom villain with id = hero.villains")]
      (is (= 200 (:status (first (get-in result [:hero :details])))))
      (is (= 200 (:status (second (get-in result [:hero :details])))))
      (is (= 200 (:status (first  (first (get-in result [:villain :details]))))))
      (is (= 200 (:status (second (first (get-in result [:villain :details]))))))
      (is (= 200 (:status (first  (second (get-in result [:villain :details]))))))
      (is (= 200 (:status (second (second  (get-in result [:villain :details]))))))
      (is (= [{:villains ["1" "2"]} {:villains ["1" "2"]}] (get-in result [:hero :result])))
      (is (= [[{:hi "I'm villain", :id "1"} {:hi "I'm villain", :id "2"}]
              [{:hi "I'm villain", :id "1"} {:hi "I'm villain", :id "2"}]] (get-in result [:villain :result])))))

  (with-routes!
    {"/heroes"    (route-response [{:villains ["1" "2"]} {:villains ["3" "4"]}])
     "/villain/1" (villain-route "1")
     "/villain/2" (villain-route "2")
     "/villain/3" (villain-route "3")
     "/villain/4" (villain-route "4")}
    (let [result (execute-query uri "from heroes\nfrom villain with id = heroes.villains")]
      (is (= 200 (get-in result [:heroes :details :status])))
      (is (= 200 (:status (first  (first  (get-in result [:villain :details]))))))
      (is (= 200 (:status (second (first  (get-in result [:villain :details]))))))
      (is (= 200 (:status (first  (second (get-in result [:villain :details]))))))
      (is (= 200 (:status (second (second (get-in result [:villain :details]))))))
      (is (= [{:villains ["1" "2"]} {:villains ["3" "4"]}] (get-in result [:heroes :result])))
      (is (= [[{:hi "I'm villain", :id "1"} {:hi "I'm villain", :id "2"}]
              [{:hi "I'm villain", :id "3"} {:hi "I'm villain", :id "4"}]] (get-in result [:villain :result])))))

  (with-routes!
    {"/hero"      (route-response {:villains "1"})
     "/villain/1" (route-response {:weapons ["dagger" "sword"]})
     "/weapon/dagger" (route-response {:name "dagger"})
     "/weapon/sword" (route-response {:name "sword"})}
    (let [result (execute-query uri "from hero\n
                                     from villain with id = hero.villains\n
                                     from weapon with id = villain.weapons")]
      (is (= {:villains "1"} (get-in result [:hero :result])))
      (is (= {:weapons ["dagger" "sword"]} (get-in result [:villain :result])))
      (is (= [{:name "dagger"} {:name "sword"}] (get-in result [:weapon :result])))))

  (with-routes!
    {"/hero"      (route-response {:villains ["1"]})
     "/villain/1" (route-response {:weapons ["dagger" "sword"]})
     "/weapon/dagger" (route-response {:name "dagger"})
     "/weapon/sword" (route-response {:name "sword"})}
    (let [result (execute-query uri "from hero\n
                                     from villain with id = hero.villains\n
                                     from weapon with id = villain.weapons")]
      (is (= {:villains ["1"]} (get-in result [:hero :result])))
      (is (= [{:weapons ["dagger" "sword"]}] (get-in result [:villain :result])))
      (is (= [[{:name "dagger"} {:name "sword"}]] (get-in result [:weapon :result])))))

  (testing "With map->list->simple_value"
    (with-routes!
      {"/hero"          (route-response {:villains [{:id "1" :weapon "DAGGER"}]})
       "/villain/1"     (route-response {:id "1"})
       "/weapon/DAGGER" (route-response {:id "DAGGER"})}
      (let [result (execute-query uri "from hero \n
                                      from villain with id = hero.villains.id \n
                                      from weapon with id = hero.villains.weapon")]
        (is (= {:villains [{:id "1" :weapon "DAGGER"}]} (get-in result [:hero :result])))
        (is (= [{:id "1"}] (get-in result [:villain :result])))
        (is (= [{:id "DAGGER"}] (get-in result [:weapon :result]))))))

  (testing "With map->list->complex_value"
    (with-routes!
      {"/hero"                  (route-response {:villains [{:v {:id "1"}}]})
       "/villain/{\"id\":\"1\"}"  (route-response {:id "1"})}
      (let [result (execute-query uri "from hero \n
                                      from villain with id = hero.villains.v")]
        (is (= {:villains [{:v {:id "1"}}]} (get-in result [:hero :result])))
        (is (= [{:id "1"}] (get-in result [:villain :result]))))))

  (testing "With list->map->list->simple_value and list->map->list->complex_value"
    (with-routes!
      {"/heroes"         (route-response [{:villains [{:id "1" :weapons ["DAGGER"]}]}])
       "/villain/1"      (route-response {:id "1"})
       "/weapon/DAGGER"  (route-response {:id "DAGGER"})}
      (let [result (execute-query uri "from heroes\n
                                      from villain with id = heroes.villains.id\n
                                      from weapon with id = heroes.villains.weapons")]
        (is (= [{:villains [{:id "1" :weapons ["DAGGER"]}]}] (get-in result [:heroes :result])))
        (is (= [[{:id "1"}]] (get-in result [:villain :result])))
        (is (= [[[{:id "DAGGER"}]]] (get-in result [:weapon :result])))))))

(deftest request-with-ignore-errors-should-have-ignore-errors-in-metadata
  (with-routes!
    {"/hero" (assoc (hero-route) :status 500)}
    (let [result (execute-query uri "from hero ignore-errors")]
      (is (= 500 (get-in result [:hero :details :status])))
      (is (= {:ignore-errors "ignore"} (get-in result [:hero :details :metadata]))))))

(deftest error-request-with-ignore-errors-shouldnt-throw-exception
  (with-routes!
    {"/hero" (assoc (hero-route) :status 500)}
    (let [result (execute-query uri "from hero ignore-errors")]
      (is (= 500 (get-in result [:hero :details :status])))
      (is (= {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]} (get-in result [:hero :result]))))))

(deftest request-with-encoder
  (with-routes!
    {(route-request "/hero" {:bag {:capacity 10}}) (hero-route)}
    (let [result (execute-query uri "from hero with bag = {capacity: 10} -> json")]
      (is (= 200 (get-in result [:hero :details :status])))
      (is (= {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]} (get-in result [:hero :result])))))
  (with-routes!
    {(route-request "/hero" {:bag "[10,20]"}) (hero-route)}
    (let [result (execute-query uri "from hero with bag = [10, 20] -> flatten -> json")]
      (is (= 200 (get-in result [:hero :details :status])))
      (is (= {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]} (get-in result [:hero :result]))))))

(deftest request-with-encoder-2
  (with-routes!
    {"/hero" (hero-with-bag-route)
     (route-request "/sidekick" {:bag {:capacity 10}}) (sidekick-route)}
    (let [result (execute-query uri "from hero \n from sidekick with bag = hero.bag -> json")]
      (is (= 200 (get-in result [:hero :details :status])))
      (is (= {:hi "I'm hero", :bag {:capacity 10}} (get-in result [:hero :result])))
      (is (= 200 (get-in result [:sidekick :details :status])))
      (is (= {:hi "I'm sidekick"} (get-in result [:sidekick :result]))))))

(deftest request-with-quoted-param
  (with-routes!
    {(route-request "/hero" {:name "Dwayne \"The Rock\" Johnson"}) (hero-route)}
    (let [result (execute-query uri "from hero with name = $name" {:name "Dwayne \"The Rock\" Johnson"})]
      (is (= 200 (get-in result [:hero :details :status]))))))

(deftest request-with-debug
  (with-routes!
    {(route-request "/hero" {:name "Bioman"}) (hero-route)}
    (let [result (execute-query uri "from hero with name = $name" {:name "Bioman"} {:debugging true})]
      (is (= 200 (get-in result [:hero :details :status]))
          (contains? (get-in result [:hero :details]) :debug)))))

(deftest execute-query-post
  (testing "Execute post with simple body"
    (with-routes!
      {(fn [request]
         (and (= (:path request) "/hero")
              (= (:method request) "POST")
              (= (get-stub-body request) (json/generate-string {:id 1})))) (hero-route)}
      (let [result (execute-query uri "to hero with id = 1")]
        (is (= 200 (get-in result [:hero :details :status]))))))

  (testing "Execute post with simple body and path var"
    (with-routes!
      {(fn [request]
         (and (= (:path request) "/villain/1")
              (= (:method request) "POST")
              (= (get-stub-body request) (json/generate-string {:name "Jocker"})))) (hero-route)}
      (let [result (execute-query uri "to villain with id = 1, name = \"Jocker\"")]
        (is (= 200 (get-in result [:villain :details :status])))))))

(deftest execute-query-patch
  (testing "Execute patch with simple body"
    (with-routes!
      {(fn [request]
         (and (= (:path request) "/villain/1")
              (= (:method request) "PATCH"))) (hero-route)}
      (let [result (execute-query uri "update villain with id = 1, name = \"Thanos\"")]
        (is (= 200 (get-in result [:villain :details :status])))))))

(deftest request-with-float-param
  (testing "In-query float param should not be considered a chained call"
    (with-routes!
      {(fn [request]
         (and (= (:path request) "/hero")
              (= (:method request) "POST")
              (= (get-stub-body request) (json/generate-string {:version 1.5})))) (hero-route)}
      (let [result (execute-query uri "to hero with version = 1.5")]
        (is (= 200 (get-in result [:hero :details :status])))))))

(deftest request-with-param-map
  (with-routes!
    {(route-request "/hero" {:name "Jiraiya" :age "45"}) (hero-route)}
    (let [result (execute-query uri "from hero with $hero" {:hero {:name "Jiraiya" :age 45}})]
      (is (= 200 (get-in result [:hero :details :status]))))))

(deftest request-with-multiplexed-param-map
  (with-routes!
    {(route-request "/hero" {:name "Jiraiya"}) (hero-route)
     (route-request "/hero" {:name "Jaspion"}) (hero-route)}
    (let [response (execute-query uri "from hero with $hero" {:hero {:name ["Jiraiya" "Jaspion"]}})
          details (get-in response [:hero :details])
          result (get-in response [:hero :result])]
      (is (= 200 (:status (first details))))
      (is (= 200 (:status (second details))))
      (is (= [{:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]}
              {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]}] result)))))

(deftest chained-call
  (with-routes! {"/hero" (hero-route) "/sidekick" (sidekick-route)}
    (let [result (execute-query uri "from hero\nfrom sidekick")]
      (is (= 200 (get-in result [:hero :details :status])))
      (is (= {:hi "I'm hero", :sidekickId "A20" :villains ["1" "2"] :weapons ["pen" "papel clip"]} (get-in result [:hero :result])))
      (is (= 200 (get-in result [:sidekick :details :status])))
      (is (= {:hi "I'm sidekick"} (get-in result [:sidekick :result]))))))

(deftest with-params
  (with-routes! {"/product/1234" (product-route 1234)}
    (let [result (execute-query uri "from product with id = $id" {:id "1234"})]
      (is (= 200 (get-in result [:product :details :status])))
      (is (= {:product "1234"} (get-in result [:product :result]))))))

(deftest failing-request-debug-mode
  (let [uri "http://not.a.working.endpoint"
        result (execute-query uri "from fail" {} {:debugging true})]
    (is (= 0 (get-in result [:fail :details :status])))
    (is (= "http://not.a.working.endpoint" (get-in result [:fail :details :debug :url])))
    (is (= 5000 (get-in result [:fail :details :debug :timeout])))))

(deftest request-with-overlapping-headers
  (testing "Query without query headers"
    (with-routes!
      {(route-header "/hero" {"test" "test", "content-type" "application/json"})
       (hero-route)}
      (let [result (execute-query uri "from hero" {} {:forward-headers {"restql-query-control" "ad-hoc", "test" "test", "accept" "*/*"}})]
        (is (= 200 (get-in result [:hero :details :status]))))))

  (testing "Replacing request headers with query headers"
    (with-routes!
      {(route-header "/hero" {"test" "diff"})
       (hero-route)}
      (let [result (execute-query uri "from hero \nheaders Test = \"diff\"" {} {:forward-headers {"restql-query-control" "ad-hoc", "test" "test", "accept" "*/*"}})]
        (is (= 200 (get-in result [:hero :details :status]))))))

  (testing "One request with fowarded-headers, one request with query headers"
    (with-routes!
      {(route-header "/hero" {"test" "test"})
       (hero-route)
       (route-header "/sidekick" {"test" "diff"})
       (sidekick-route)}
      (let [result (execute-query uri "from hero \nfrom sidekick\nheaders Test = \"diff\"" {} {:forward-headers {"restql-query-control" "ad-hoc", "test" "test", "accept" "*/*"}})]
        (is (= 200 (get-in result [:hero :details :status]))))))

  (testing "Replace default content-type header with query content-type header"
    (with-routes!
      {(route-header "/hero" {"content-type" "text/plain"})
       (hero-route)}
      (let [result (execute-query uri "from hero" {} {:forward-headers {"Content-Type" "text/plain"}})]
        (is (= 200 (get-in result [:hero :details :status])))))))

(deftest request-and-chained-with-missing-params
  (testing "Request with missing param should not be skipped"
    (with-routes!
      {(route-request "/hero") (hero-route)}
      (let [result (execute-query uri "from hero with name = $name, id = $id")]
        (is (= 200 (get-in result [:hero :details :status]))))))

  (testing "Chained request with chained-param nonexistent should not be skipped"
    (with-routes!
      {"/hero" (hero-route)
       "/sidekick" (sidekick-route)}
      (let [result (execute-query uri
                                  "from hero\n from sidekick with name = hero.missing_param")]
        (is (= 200 (get-in result [:hero :details :status])))
        (is (= 200 (get-in result [:sidekick :details :status]))))))

  (testing "Chained request with nil param should not be skipped"
    (with-routes!
      {"/hero" (hero-route)
       "/sidekick" (sidekick-route)}
      (let [result (execute-query uri "from hero \n
                                       from sidekick with name = hero.name, id = $id"
                                  {:id nil})]
        (is (= 200 (get-in result [:hero :details :status])))
        (is (= 200 (get-in result [:sidekick :details :status]))))))

  (testing "Chained request should be skipped if parent request status code > 399 or < 200"
    (with-routes!
      {"/hero" (assoc (hero-route) :status 500)
       "/sidekick" (sidekick-route)}
      (let [result (execute-query uri "from hero\n from sidekick with name = hero.name, id = hero.id")]
        (is (= 500 (get-in result [:hero :details :status])))
        (is (= 400 (get-in result [:sidekick :details :status])))
        (is (= "The request was skipped due to missing {:name, :id} param value" (get-in result [:sidekick :result])))))))

(deftest request-with-null-header-value
  (with-routes!
    {(route-header "/hero" {"test" ""})
     (hero-route)}
    (let [result (execute-query uri "from hero headers test = $test" {} {:forward-headers {"restql-query-control" "ad-hoc", "accept" "*/*"}})]
      (is (= 200 (get-in result [:hero :details :status]))))))

(deftest request-with-flatten

  (testing "Flatten single value"
    (with-routes!
      {"/hero" (hero-route)
       (route-request "/sidekick" {:id "I'm hero"}) (sidekick-route)}
      (let [result (execute-query uri "from hero \n from sidekick with id = hero.hi -> flatten")]
        (is (= 200 (get-in result [:hero :details :status])))
        (is (= 200 (get-in result [:sidekick :details :status]))))))

  (testing "Flatten list value"
    (with-routes!
      {"/hero" (hero-route)
       (route-request "/sidekick" {:id "2"}) (sidekick-route)}
      (let [result (execute-query uri "from hero \n from sidekick with id = hero.villains -> flatten")]
        (is (= 200 (get-in result [:hero :details :status])))
        (is (= 200 (get-in result [:sidekick :details :status]))))))

  (testing "Flatten path list value"
    (with-routes!
      {"/hero" {:status 200 :body (json/generate-string {:villains [{:id "1" :weapon {:name "FINGGER"}}
                                                                    {:id "2" :weapon {:name "FIREGUN"}}]})}
       (route-request "/sidekick" {:id "FIREGUN"}) (sidekick-route)}
      (let [result (execute-query uri "from hero \n from sidekick with id = hero.villains.weapon.name -> flatten")]
        (is (= 200 (get-in result [:hero :details :status])))
        (is (= 200 (get-in result [:sidekick :details :status])))))))

(deftest with-list-param
  (testing "List with single value"
    (with-routes!
      {(route-request "/hero" {:id "1"})
       (route-response {:villains []})}
      (let [result (execute-query uri "from hero with id = [\"1\"]")]
        (is (= 200 (:status (first (get-in result [:hero :details])))))
        (is (= [{:villains []}] (get-in result [:hero :result]))))))

  (testing "List with multiple value"
    (with-routes!
      {(route-request "/hero" {:id "1"})
       (route-response {:villains ["a"]})
       (route-request "/hero" {:id "2"})
       (route-response {:villains ["b"]})}
      (let [result (execute-query uri "from hero with id = [\"1\", \"2\"]")]
        (is (= 200 (:status (first (get-in result [:hero :details])))))
        (is (= 200 (:status (second (get-in result [:hero :details])))))
        (is (= [{:villains ["a"]} {:villains ["b"]}] (get-in result [:hero :result]))))))

  (testing "List with single map value"
    (with-routes!
      {(route-request "/hero" {:id {:num "1"}})
       (route-response {:villains []})}
      (let [result (execute-query uri "from hero with id = [{num: \"1\"}]")]
        (is (= 200 (:status (first (get-in result [:hero :details])))))
        (is (= [{:villains []}] (get-in result [:hero :result]))))))

  (testing "List with multiple map value"
    (with-routes!
      {(route-request "/hero" {:id {:num "1"}})
       (route-response {:villains ["a"]})
       (route-request "/hero" {:id {:num "2"}})
       (route-response {:villains ["b"]})}
      (let [result (execute-query uri "from hero with id = [{num: \"1\"},{num: \"2\"}]")]
        (is (= 200 (:status (first (get-in result [:hero :details])))))
        (is (= 200 (:status (second (get-in result [:hero :details])))))
        (is (= [{:villains ["a"]} {:villains ["b"]}] (get-in result [:hero :result]))))))

  (testing "List flatten multiple value"
    (with-routes!
      {(route-request "/hero" {:id "2"})
       (route-response {:villains ["b"]})}
      (let [result (execute-query uri "from hero with id = [\"1\", \"2\"] -> flatten")]
        (is (= 200 (:status (get-in result [:hero :details]))))
        (is (= {:villains ["b"]} (get-in result [:hero :result]))))))

  (testing "Map with multiple flatten map value"
    (with-routes!
      {(route-request "/hero" {:id {:num "2"}})
       (route-response {:villains ["b"]})}
      (let [result (execute-query uri "from hero with id = [{num: \"1\"},{num: \"2\"}] -> flatten")]
        (is (= 200 (:status (get-in result [:hero :details]))))
        (is (= {:villains ["b"]} (get-in result [:hero :result])))))))

(deftest request-with-nested-multiplex-and-flatten
  (testing "Nested map multiplexed"
    (with-routes!
      {(route-request "/weapons")
       (route-response {:swords [{:name "1"} {:name "2"}]
                        :types {:typeId "8"}
                        :source "MAGICAL"})
       (route-request "/sidekick" {:weapon {:name "1" :typeId "8" :invisible false} :source "MAGICAL"})
       (route-response {:ok "Yeap!"})
       (route-request "/sidekick" {:weapon {:name "2" :typeId "8" :invisible false} :source "MAGICAL"})
       (route-response {:ok "Yeah!"})}
      (let [result (execute-query uri "from weapons hidden\n
                                       from sidekick\n
                                       with\n
                                         source = weapons.source\n
                                         weapon = {\n
                                           name: weapons.swords.name,\n
                                           typeId: weapons.types.typeId,\n
                                           invisible: false\n
                                         }")]
        (is (= {:ok "Yeap!"} (first (get-in result [:sidekick :result]))))
        (is (= {:ok "Yeah!"} (second (get-in result [:sidekick :result])))))))

  (testing "Nested map multiplexed with value flattened"
    (with-routes!
      {(route-request "/weapons")
       (route-response {:swords [{:name "1"} {:name "2"}]
                        :types [{:typeId "7"} {:typeId "8"} {:typeId "9"}]
                        :source "MAGICAL"})
       (route-request "/sidekick" {:weapon {:name ["1","2"] :typeId "7" :invisible false} :source "MAGICAL"})
       (route-response {:ok "Yeap"})
       (route-request "/sidekick" {:weapon {:name ["1","2"] :typeId "8" :invisible false} :source "MAGICAL"})
       (route-response {:ok "Yeap"})
       (route-request "/sidekick" {:weapon {:name ["1","2"] :typeId "9" :invisible false} :source "MAGICAL"})
       (route-response {:ok "Yeap"})}
      (let [result (execute-query uri "from weapons hidden\n
                                       from sidekick\n
                                       with\n
                                         source = weapons.source\n
                                         weapon = {\n
                                           name: weapons.swords.name -> flatten,\n
                                           typeId: weapons.types.typeId,\n
                                           invisible: false\n
                                         }")]
        (is (= [{:ok "Yeap"} {:ok "Yeap"} {:ok "Yeap"}] (get-in result [:sidekick :result]))))))

  (testing "Nested map multiplexed flattened"
    (with-routes!
      {(route-request "/weapons")
       (route-response {:swords [{:name "1"} {:name "2"}]
                        :types [{:typeId "7"} {:typeId "8"} {:typeId "9"}]
                        :source "MAGICAL"})
       (route-request "/sidekick" {:weapon {:name "2" :typeId "8" :invisible false} :source "MAGICAL"})
       (route-response {:ok "Yeap"})}
      (let [result (execute-query uri "from weapons hidden\n
                                       from sidekick\n
                                       with\n
                                         source = weapons.source\n
                                         weapon = {\n
                                           name: weapons.swords.name,\n
                                           typeId: weapons.types.typeId,\n
                                           invisible: false\n
                                         } -> flatten")]
        (is (= {:ok "Yeap"} (get-in result [:sidekick :result]))))))

  (testing "Nested map multiplexed flattened with value flattened"
    (with-routes!
      {(route-request "/weapons")
       (route-response {:swords [{:name "1"} {:name "2"}]
                        :types [{:typeId "7"} {:typeId "8"}]
                        :source "MAGICAL"})
       (route-request "/sidekick" {:weapon {:name ["1","2"] :typeId "8" :invisible false} :source "MAGICAL"})
       (route-response {:ok "Yeap"})}
      (let [result (execute-query uri "from weapons hidden\n
                                       from sidekick\n
                                       with\n
                                         source = weapons.source\n
                                         weapon = {\n
                                           name: weapons.swords.name -> flatten,\n
                                           typeId: weapons.types.typeId,\n
                                           invisible: false\n
                                         } -> flatten")]
        (is (= {:ok "Yeap"} (get-in result [:sidekick :result])))))))

(deftest with-exception
  (testing "If an exception occurs returns it in exception-ch."
    (with-routes!
      {"/hero" (hero-route)}
      (with-redefs [restql.core.runner.executor/single-request-not-multiplexed?
                    (fn [_] (throw (Exception. "exceptional")))]
        (is (= "exception"
               (->
                (restql/execute-query-channel :mappings {:hero (str uri "/hero")}
                                              :encoders {}
                                              :query (restql.parser.core/parse-query "from hero" :context {})
                                              :query-opts {})
                (second)
                (clojure.core.async/<!!)
                :type))))))

  (testing "If an exception occurs returns it"
    (with-redefs [restql.core.runner.executor/single-request-not-multiplexed?
                  (fn [_] (throw (Exception. "exceptional")))]
      (is (= "exception"
             (:type (execute-query "http://0.0.0.0" "from hero"))))))

  (testing "If an exception occurs pass it to the callback"
    (with-redefs [restql.core.runner.executor/single-request-not-multiplexed?
                  (fn [_] (throw (Exception. "exceptional")))]
      (let [p (promise)]
        (restql/execute-query-async :mappings {:hero "http://0.0.0.0/hero"}
                                    :encoders {}
                                    :query "from hero"
                                    :params {}
                                    :callback (fn [v] (deliver p v)))
        (is (= "exception"
               (:type @p)))))))

(deftest stop-waiting-requests
  (let [counter (atom 0)
        encoders {}
        mappings {:hero "http://0.0.0.0/hero" :sidekick "http://0.0.0.0/sidekick"}
        query (restql.parser.core/parse-query "from hero \n from sidekick with id = hero.sidekickId")]

    (testing "If a exception occurs returns."
      (reset! counter 0)
      (with-redefs [restql.core.runner.executor/single-request-not-multiplexed?
                    (fn [_] (do (swap! counter inc) (throw (Exception. "exceptional"))))]
        (do
          (->>
           (restql/execute-query-channel :mappings mappings
                                         :encoders encoders
                                         :query query
                                         :query-opts {})
           (second)
           (<!!))
          (is (> 2 @counter)))))))

(deftest request-with-param-with-brackets
  (with-routes!
    {(route-request "/hero" {:name%5B%5D  "Dwayne \"The Rock\" Johnson"}) (hero-route)}
    (let [result (execute-query uri "from hero with name[] = $name" {:name "Dwayne \"The Rock\" Johnson"})]
      (is (= 200 (get-in result [:hero :details :status]))))))