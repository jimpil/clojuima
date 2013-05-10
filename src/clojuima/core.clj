(ns clojuima.core
 (:import  [org.apache.uima UIMAFramework]
  	   [org.apache.uima.jcas JCas]
           [org.apache.uima.jcas.tcas Annotation]
  	   [org.apache.uima.resource ResourceSpecifier ResourceManager]
  	   [org.apache.uima.util XMLInputSource CasPool]
  	   [org.apache.uima.analysis_engine AnalysisEngine]
           [org.uimafit.component JCasAnnotator_ImplBase]
           [org.uimafit.pipeline SimplePipeline]
           [org.uimafit.util JCasUtil CasUtil]
           [org.uimafit.component.initialize ConfigurationParameterInitializer]
           [org.uimafit.factory JCasFactory TypeSystemDescriptionFactory AnalysisEngineFactory AggregateBuilder CollectionReaderFactory]
           [clojuima.java UIMAProxy]
           [org.apache.uima.examples.tagger HMMTagger]
           [org.apache.uima TokenAnnotation SentenceAnnotation]
  ))

(def dynamic-classloader (. (Thread/currentThread) getContextClassLoader))

(definline xml-parser "Get the current XML parser." []
 `(UIMAFramework/getXMLParser))

(definline logger "Get the current logger." []
 `(UIMAFramework/getLogger)) 

(defn ^ResourceSpecifier xml-resource  
 "Parses an xml-descriptor file and returns the ResourceSpecifier object."
  [xml-descriptor-loc]
  (let [source (XMLInputSource. xml-descriptor-loc)]
(.parseResourceSpecifier (xml-parser) source)))

(defn ^JCas jcas 
 "Create a JCas, given an Analysis Engine (ae)."
 [^AnalysisEngine ae] 
  (.newJCas ae))

(defn produce-primitive
 "Produce UIMA components from your objects without writing any XML descriptors, via uima-fit."
 [& os]
 (map #(AnalysisEngineFactory/createPrimitive (class %)  
  (TypeSystemDescriptionFactory/createTypeSystemDescription) (to-array [])) os))

(defn produce 
 "Produce UIMA components according to some ResourceSpecifier objects (which you get from calling (xml-resource some.xml)."
[what specifier config-map & {:keys [resource-manager] 
                              :or {resource-manager  (doto (UIMAFramework/newDefaultResourceManager) 
                                                         (.setExtensionClassPath dynamic-classloader "" true))}}]
(let [min-params {"TIMEOUT_PERIOD" 0 
                  ;"NUM_SIMULTANEOUS_REQUESTS" (int par-requests)
                  "RESOURCE_MANAGER" resource-manager}
      additional-params (merge min-params config-map)] 
(case what
	:analysis-engine  (UIMAFramework/produceAnalysisEngine specifier additional-params)                           
	:cas-consumer     (UIMAFramework/produceCasConsumer specifier additional-params)
	:cas-initializer  (UIMAFramework/produceCasInitializer specifier additional-params)
	:collection-processing-engine (UIMAFramework/produceCollectionProcessingEngine specifier additional-params)
	:collection-reader (UIMAFramework/produceCollectionReader specifier additional-params)
        :primitive-ae (produce-primitive specifier)))) ;;'specifier' here really means 'object-instance' and not xml as we're going through uima-fit
 
 (defn inject-annotation! [^JCas jc [^Class type  begin end]]
  (let [cas (.getCas jc)
        type-system (.getTypeSystem jc) 
        type (CasUtil/getAnnotationType cas type)]   
 (.addFsToIndexes cas 
    (.createAnnotation cas type begin end))))

(defn select-annotations [^JCas jc ^Class klass start end]
  (JCasUtil/selectCovered jc klass start end))

(defn calculate-indices [original matches]
  (let [matcher (fn ^java.util.regex.Matcher [p] 
                  (re-matcher (re-pattern p) original))]   
  (with-local-vars [cpos 0]
  (reduce 
    #(assoc %1 %2 
       (let [ma1 (doto (matcher %2) (.find @cpos))
             endpos (.end ma1)]
        (var-set cpos endpos) 
        [(.start ma1) endpos])) 
    {} matches))))


(defn uima-compatible 
  "Given a component and a function to extract the desired input from the JCas, 
  returns a UIMA compatible oblect that wraps the original component. For now the component must be able to act as a function.
  The fn  'jcas-input-extractor' must accept 2 arguments [JCas, UIMAContext]." 
  ([component jcas-input-extractor jcas-writer config-map]
   (produce :analysis-engine  
    (AnalysisEngineFactory/createPrimitiveDescription UIMAProxy 
       (to-array  [UIMAProxy/PARAM_ANNFN  (class component)  
                   UIMAProxy/PARAM_EXTFN  (class jcas-input-extractor)
                   UIMAProxy/PARAM_POSTFN (class jcas-writer)])) config-map))
  ([component jcas-input-extractor jcas-writer] 
    (uima-compatible component jcas-input-extractor jcas-writer {})) )
    
    
(defn hmm-postag
 "A HMM POS-tagger capable of working not only with bigrams(n=2) but also trigrams(n=3).
 The 3rd overload is the important one. It expects the path to the model you want to use and a map from sentences (String) to tokens (seq of Strings).
 If you don't have the tokens for the sentences simply pass nil or an empty seq and UIMA's basic WhiteSpaceTokenizer will be used to create the tokens.
 Theoretically you can mix non-empty token-seqs with  empty ones in the same map - however if that is the case, the tokens that were supplied will be ignored
 only to be re-tokenized by the WhiteSpaceTokenizer. Therefore, it is not suggested that you do that. In fact, it not suggested to use the WhiteSpaceTokenizer 
 for any serious work at all. In addition, you can also pass an extra key :n with value 2 or 3, to specify whether you want bigram or trigram modelling.  
 The other 2 overloads are there to simply deal with a single sentence."
([path-to-model whole-sentence tokens n]  
  (hmm-postag path-to-model {whole-sentence tokens :n n}))  
([path-to-model whole-sentence tokens] 
  (hmm-postag path-to-model whole-sentence tokens 3)) 
([path-to-model sentence-token-map]
  (let [config {"NGRAM_SIZE" (int (or (:n sentence-token-map) 3))
                "ModelFile"  path-to-model}
        proper-map  (dissoc sentence-token-map :n)
        need-aggregate? (not (every? seq (vals proper-map)))        
        tagger (if need-aggregate?
                 (produce :analysis-engine (-> "HmmTaggerAggregate.xml" clojure.java.io/resource xml-resource) config)
                 (produce :analysis-engine (-> "HmmTagger.xml" clojure.java.io/resource xml-resource) config))       
        jc (jcas tagger)
        pos-tag (fn [^String s ^JCas jc]
                    (.setDocumentText jc s)
                    (.process tagger jc) 
                      (mapv (fn [^TokenAnnotation ta] (.getPosTag ta)) 
                        (select-annotations jc TokenAnnotation 0 (count s))))]
 (if need-aggregate? 
  (reduce-kv 
    (fn [init sentence tokens]
     (conj init (pos-tag sentence jc))) [] proper-map)        
  (reduce-kv 
    (fn [init sentence tokens] 
     (conj init (do (inject-annotation! jc [SentenceAnnotation 0 (count sentence)])
                    (doseq [[_ [b e]] (calculate-indices sentence tokens)]
                      (inject-annotation! jc [TokenAnnotation b e])) 
                  (pos-tag sentence jc))))
   [] proper-map)))) )
   
(def postag-brown (partial hmm-postag "/home/sorted/clooJWorkspace/hotel-nlp/resources/pretrained_models/BrownModel.dat"))    
   
(comment
 
-----CLOJURE->UIMA---------
 
 (def sample "All plants need light and water to grow!")  ;;define a sample sentence
 (def token-regex "Regular expression capable of identifying word boundaries." 
   #"[\p{L}\d/]+|[\-\,\.\?\!\(\)]")

 (defn my-tok "A tokenizing fucntion" 
  [^String s] 
  (re-seq token-regex s))

 (my-tok sample)
 
 (defn extractor [^JCas c _] ;;2nd arg is the UIMAContext which we don't need for our purpose
      (.getDocumentText c)) 

 (defn post-fn 
 "Given a JCas, some annotation result and the original input, calculate the appropriate indices and inject them into the CAS. " 
  [^JCas jc res original-input]  
   (inject-annotation! jc [Annotation 0 (count original-input)]) ;;the entire sentence annotation    
    (doseq [[_ [b e]] (calculate-indices original-input res)]
      (inject-annotation! jc [Annotation b e])) );; the token annotations

(uima-compatible my-tok extractor post-fn)
(def my-ae *1) ;;got our UIMA tokenizer
 
(def jc (JCasFactory/createJCas))
 (.setDocumentText jc  sample)
 (.process my-ae jc)
 
 clojuima.java.UIMAProxy/resultMap
 
 
--------UIMA->CLOJURE--------- 
 
(postag-brown sample (re-seq token-regex sample) 3)   ;;providing the tokens so WhitespaceTokenizer won't be used

(postag-brown sample nil 2) ;;not providing the tokens so WhitespaceTokenizer will be used
 
)   
      
    
    
 
