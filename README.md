# clojuima

# From [Clojure](http://clojure.org/) to [uimaFIT](https://code.google.com/p/uimafit/) to [UIMA](http://uima.apache.org/) and back...

----
## What is this?
This is a demonstration/walthrough (+ a sample project) of how to: 
  
1. Turn regular Clojure functions/defrecords/deftypes into UIMA compatible components, thus leveraging UIMA's workflow/evaluation capabilities.   
2. Use UIMA-components as regular functions in your Clojure code, thus leveraging the plethora of components out there. 


----
## Requirements
Combining Clojure with UIMA presents quite a unique set of problems...Apart from highly stateful, UIMA
relies heavily on the java.lang.reflect API for instantiating Classes and is tightly coupled to XML descriptors. Some of the XML nonsense however, disappears if we go through uimaFIT. Therefore, I'm assuming:

* Some familiarity with UIMA's idiosyncrasies - in particular how UIMA loads and instantiates classes via reflection. I am by no means claiming to be a UIMA guru, quite the contrary actually.
* Good knowledge and understanding of Clojure, otherwise why would you be here? ;)
* that you can read Java code (believe it or not we're going to have to write some Java)
* that you 've visited at least once the uimaFIT [website](https://code.google.com/p/uimafit/) and read the *2 sides of uimaFIT* [section](https://code.google.com/p/uimafit/wiki/TwoSidesOfUimaFIT)
* that you 're using leiningen 2 as your project/build management tool 
* that you 're on Clojure 1.4 or higher
* that you 're on a Unix-y system (if you're not convert "/" to "\" in any shell interaction)
* finally, that you 're feeling well and up to the task... :)     


----
# Clojure -> UIMA

We are going to deal with the difficult case first, that is turning Clojure functions into UIMA components dynamically at runtime.  

The first construct that comes to mind when someone looks at how UIMA components have been implemented, is Clojure's **proxy**. If for any reason you don't know or remember what *proxy* does, stop here and refresh your memory by looking at the [documentation](http://clojuredocs.org/clojure_core/clojure.core/proxy). If you do know, what *proxy* does, you'll probably be very upset to find out that it will not work in our case! The reason being that, proxy only cares about arity and not types. In other words, we cannot override only one of the .process() methods (the one accepting JCas). There is no way to specify that in Clojure, unless you go down the **gen-class** road which steals away all the dynamicity from you...Basically, if we want to keep the dynamic nature of *proxy*, we have to implement it ourselves! Don't fear though...it's not that hard (well, once you understand where all the right knobs are!)

You can see the entire file [here](https://github.com/jimpil/clojuima/blob/master/src/clojuima/java/UIMAProxy.java). There is quite a bit of explaining though before we do anything else. Take 2 minutes to skim through the Java class to get a feel of what is happening. Don't worry if you don't understand something - all will become clear soon... :)

Ok, let's see...As you probably already gathered, UIMAProxy.java is a generic UIMA component that doesn't do much. Instead, it delegates the usual tasks to independent functions. By *usual-tasks*, we mean the absolute minimum number of tasks every single UIMA component does: 
  
1. read from the CAS to extract the input it needs to work with   
2. do whatever annotation on that input   
3. write the annotation indices back to the CAS   

These are 3 distinct operations and we need to abstract them if we want to convert any old class into UIMA compatible. Before we move on to Clojure, let's observe the class a bit more...

1.initialize() is not very interesting but we need to clarify certain things. Clojure keeps class files in memory, where UIMA's classloader, or in fact Java's default one, can't look. In our case, we're going through uimaFIT, which uses Spring to implicitely convert Class objects to Class-name strings and vice-versa, which in turn will use the thread context classloader (Thread.currentThread().getContextClassLoader()), if it can't find a particular class by name when using the default classloader. Whether this is by design or by accident, little do we care...As long as we're going through Spring we're safe, otherwise, if we wanted to use Class.forName().newInstance(), we'd have to use the 3-arg overload of Class.forName(), passing Clojure's dynamic Classloader as the last argument.    
2. You'll notice that I'm inheriting from uimaFIT's base implementation (org.uimafit.component.JCasAnnotator_ImplBase) and not from UIMA's. Again, this is important.  
3. The rest interesting stuff all happens in the doActualWork() method. It accepts classes of functions that have been passed in via annotations and creates new instances of them. The usual case would be to pass in 3 distinct functions for the 3 distinct jobs, but other people have suggested that it would be nice to be able to pass in a single function that does everything (after all it is a CAS->CAS tranformation; or should I say 'mutation'?). Well, with this class you can pass nils for the ext-fn and post-fn but you have to make absolutely sure that the annotator-fn can handle everything (reading from and writing to the CAS).  


###and that is it! 
There will be no more Java from now on...our proxy is ready! We can use this class to wrap the functions that will do the actual work, thus having an UIMA component that essentially delegates to our functions.
One important thing to remember is that you can either pass all 3 functions or only the one that is going to do the annotation. In the latter case that function should be able to perform all the 3 aformentioned tasks. 

For the rest of this section, we're going to create a dummy project to test our newly created *proxy* using lein2. Go to your usual workspace, fire up a terminal in that directory and type:

    
    lein2 new clojuima
    cd clojuima

open your project.clj with any text editor and add the following 2 dependencies:

    [org.apache.uima/uimaj-core "2.4.0"]
    [org.uimafit/uimafit "1.4.0"]

In addition add the following entry to your project map:   
*:java-source-paths ["src/clojuima/java"]* 

Now, with our dependencies sorted let's prepare our namespace. Navigate into src/clojuima and open up the file core.clj. Make your namespace declaration look like this one (basically add all these imports):

     (ns clojuima.core
      (:import 
           [org.apache.uima UIMAFramework]
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
           [clojuima.java UIMAProxy]))


Finally, download UIMAProxy.java from [here](http://pastebin.com/ySkq61uZ), replace the package declaration at the very top with "package clojuima.java;" and save it in .../.../clojuima/src/java. If the directory doesn't exist create it. Everything should be at the right place and ready for compilation at this point. 

In order to keep the code clean and idiomatic, we will define a bunch of helper functions that wrap some basic UIMA functionality. We could skip this bit but I find the code becomes too obscured when doing so much interop. Copy and paste the following helpers in your core.clj.   
``` clojure

(def dynamic-classloader (. (Thread/currentThread) getContextClassLoader))

(definline xml-parser "Get the current XML parser." []
     `(UIMAFramework/getXMLParser))

(definline logger "Get the current logger." []
     `(UIMAFramework/getLogger)) 

(defn ^ResourceSpecifier  xml-resource 
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
"Produce UIMA components according to some ResourceSpecifier objects."
 [what specifier config-map 
   & {:keys [resource-manager] 
      :or {resource-manager (doto (UIMAFramework/newDefaultResourceManager) 
                             (.setExtensionClassPath dynamic-classloader "" true))}}]
(let [min-params {"TIMEOUT_PERIOD" 0 
                     "RESOURCE_MANAGER" resource-manager}
      additional-params (merge min-params config-map)] 
 (case what
     :analysis-engine  (UIMAFramework/produceAnalysisEngine specifier additional-params)                           
     :cas-consumer     (UIMAFramework/produceCasConsumer specifier additional-params)
     :cas-initializer  (UIMAFramework/produceCasInitializer specifier additional-params)
     :collection-processing-engine (UIMAFramework/produceCollectionProcessingEngine specifier additional-params)
     :collection-reader (UIMAFramework/produceCollectionReader specifier additional-params)
     :primitive-ae (produce-primitive specifier))))

(defn inject-annotation! [^JCas jc [^Class type  begin end]]
   (let [cas (.getCas jc)
         type-system (.getTypeSystem jc) 
         type (CasUtil/getAnnotationType cas type)]   
   (.addFsToIndexes cas 
     (.createAnnotation cas type begin end))))

(defn select-annotations [^JCas jc ^Class klass start end]
   (JCasUtil/selectCovered jc klass start end))

(defn calculate-indices [original matches]
  (let [^java.util.regex.Matcher matcher #(re-matcher (re-pattern %) original)]
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
returns a UIMA compatible oblect that wraps the original component. 
For now the component must be able to act as a function.
The fn  'jcas-input-extractor' must accept 2 arguments [JCas, UIMAContext]." 
([component jcas-input-extractor jcas-writer config-map]
 (produce :analysis-engine  
   (AnalysisEngineFactory/createPrimitiveDescription UIMAProxy
     (into-array String  [UIMAProxy/PARAM_ANNFN  (class component)  
                          UIMAProxy/PARAM_EXTFN  (class jcas-input-extractor)
                          UIMAProxy/PARAM_POSTFN (class jcas-writer)])) config-map))
([component jcas-input-extractor jcas-writer ] 
 (uima-compatible  component jcas-input-extractor jcas-writer {})) )
```      

We are almost there! Let's see if everything compiles...navigate back to the root of the project and type in your terminal:

    lein2 repl 
    => (load-file "src/clojuima/core.clj")

You should see output similar to this (notice the *compiling 1 source files...* message):

    lein2 repl
    Compiling 1 source files to /home/yourName/yourWorkSpace/clojuima/target/classes
    nREPL server started on port 59457
    REPL-y 0.1.10
    Clojure 1.5.1
    Exit: Control+D or (exit) or (quit)
    Commands: (user/help)
    Docs: (doc function-name-here)
          (find-doc "part-of-name-here")
    Source: (source function-name-here)
          (user/sourcery function-name-here)
    Javadoc: (javadoc java-object-or-class-here)
    Examples from clojuredocs.org: [clojuredocs or cdoc]
          (user/clojuredocs name-here)
          (user/clojuredocs "ns-here" "name-here")
    user=> (load-file "src/clojuima/core.clj")
    #'clojuima.core/uima-compatible

YAY! It compiled just fine. We can finally dive into the interesting stuff... :)   

Ok, let's observe these 10 functions for a minute. Presumably the first 5 functions need no explaining...All I'm doing is hiding the Java code. So we're left with the rest 5 to explain.

* *produce* looks bloated at first glance, but only because it streamlines the process of instantiating UIMA components of any sort. We are actually going to use only one of the *case* clauses - can you guess which? The *:analysis-engine* one, of course...the other options are essentially redundant for our purposes. Once you realise that, then it is pretty obvious that again, all I'm doing is hiding Java interop. We are very fortunate to be able to pass a map of config parameters directly into the UIMAFramework.produce() method, hence all the complicated stuff at the start where I build that map while ensuring that the ResourceManager knows about Clojure's DynamicClassloader, via its default argument. Perhaps this functions could be made better if I was aware of all the possible configuration parameters that exist within UIMA. But anyway, the consumer can always consult the docs himself and pass his own config-map that will be merged with the minimal one that the function provides ("TIMEOUT", "ResourceManager"). GIven access to an XML descriptor, the specifier object can be constructed with:   
*(xml-resource "path-to-descriptor-on-your-file-system.xml")*    

* *inject-annotation!* hopefully screams out what it's doing. It takes a JCas object and a vector of 3 elements. First element in that vector is the annotation-type we want to write (a java.lang.Class object) and the rest are the start & end offsets. That is all we need in order to write the annotations to the CAS.

* *select-annotations* again, only hides Java interop. You provide an AnnotationType and 2 offsets and it returns all the annotations of that particular type within the index boundaries you specified (via uimaFIT). 

* *calculate-indices* is interesting because in a tokenizing context it will allow us to reconstruct the indices given the original string and the individual tokens. This is important because if you've got for example a tokenizer, which has been implemented functionally instead of imperatively, it's very unlikely that you'll be able to get the indices out of it. You would typically supply a string and you'd get back a list of tokens. No state - no index... In order to make that tokenizer UIMA compatible, we need the indices no matter what. This is what this function does. It uses a Matcher object and loops through the tokens resetting the offset at the .end() of the last match. For this purposes of this demo we'll be using this function a lot. However, in a task other than tokenization or NER, you'd have to roll your own function to do the correct job. 

* *uima-compatible* is really the function that ties together all our previous efforts. It is where we were trying to get all this time, and as mentioned earlier, cannot be implemented with *proxy*. We are going to use UIMAProxy instead. IMO, the code in this function is pretty evident. It takes the 3 functions that are supposed to do the actual work. It uses uimaFIT's AnalysisEngineFactory to create a primitive-descriptor passing it our UIMAProxy (the Class object) and an array of String parameters. These, as you might have guessed, are used in pairs inside uimaFIT. So since you see 6 Strings, you know you're setting 3 configuration parameters. 


We've reached a crucial point...This is all the code we need in order to achieve our first objective. Let's try it out...go back to your REPL or start a new one and load the file as we did before. Now type:

``` clojure 
  
(in-ns 'clojuima.core)  ;; navigate to the namespace you just loaded

(def sample "All plants need light and water to grow!")  ;;define a sample sentence
(def token-regex "Regular expression capable of identifying word boundaries." 
  #"[\p{L}\d/]+|[\-\,\.\?\!\(\)]")

(defn my-tok "A tokenizing function." 
[^String s] 
(re-seq token-regex s))

(my-tok sample)
        
```
You should be seeing this exact output:

    ("All" "plants" "need" "light" "and" "water" "to" "grow" "!")

So our tokenizer/annotator works. Now we need to make it UIMA-friendly...It should be very simple after all this preparatory work...before we call *uima-compatible* we need another 2 functions. The input-extractor and the jcas-writer. For our simple example the following will do:

``` clojure

(defn extractor [^JCas c _] ;;2nd arg is the UIMAContext which we don't need for our purpose
  (.getDocumentText c)) 

(defn post-fn 
"Given a JCas, some annotation result and the original input, calculate the appropriate indices and inject them into the CAS. " 
[jc res original-input]  
(inject-annotation! jc [Annotation 0 (count original-input)]) ;;the entire sentence annotation    
 (doseq [[_ [b e]] (calculate-indices original-input res)]
   (inject-annotation! jc [Annotation b e])) );; the token annotations    
```
    
FINALLY:

     clojuima.core=>  (uima-compatible my-tok extractor post-fn)
     #<PrimitiveAnalysisEngine_impl org.apache.uima.analysis_engine.impl.PrimitiveAnalysisEngine_impl@6c96fb0f>
   
     (def my-ae *1) ;;def-it here so you don't lose it. I 'd have def-ed it directly but I wanted you to see the actual object that was returned rather than the var.

:) :) :) :) :) :) :) :) :) :)

We got our UIMA component! Let's see what it can do...type in the following:

``` clojure
    (def jc (JCasFactory/createJCas))
    (.setDocumentText jc  sample)
    (.process my-ae jc)
    
```
      
If everything went ok, you should be seeing output similar to this:

     #<ProcessTrace_impl Component Name: clojuima.java.UIMAProxy
      Event Type: Analysis
      Duration: 10ms (100%)
      >

**THAT WAS IT!** You can verify that the tokenizer actually run by typing [1] and that the annotation-indices have been correctly written to the CAS by typing [2]. The static field *resultMap* is there for testing and debugging purposes. It's generally not needed...

     clojuima.core=> clojuima.java.UIMAProxy/resultMap                   ;[1]
     {"result" ("All" "plants" "need" "light" "and" "water" "to" "grow" "!")} ;;correct
    
     clojuima.core=> (select-annotations jc Annotation 0 (count sample)) ;[2] 
     [#<DocumentAnnotation DocumentAnnotation  ;;document annotation
       sofa: _InitialView
       begin: 0
       end: 40
       language: "x-unspecified"
     > #<Annotation Annotation   ;;entire sentence annotation
       sofa: _InitialView
       begin: 0
       end: 40
     > #<Annotation Annotation   ;;1st token annotation
       sofa: _InitialView
       begin: 0
       end: 3
     > #<Annotation Annotation   ;;2nd token annotation
       sofa: _InitialView
       begin: 4
       end: 10
     > #<Annotation Annotation   ;;3rd token annotation and so forth
       sofa: _InitialView
       begin: 11
       end: 15
     > #<Annotation Annotation   ;;4th token annotation and so on...
       sofa: _InitialView
       begin: 16
       end: 21
     > #<Annotation Annotation
       sofa: _InitialView
       begin: 22
       end: 25
     > #<Annotation Annotation
       sofa: _InitialView
       begin: 26
       end: 31
     > #<Annotation Annotation
       sofa: _InitialView
       begin: 32
       end: 34
     > #<Annotation Annotation
       sofa: _InitialView
       begin: 35
       end: 39
     > #<Annotation Annotation   
       sofa: _InitialView
       begin: 39
       end: 40    ;;exclamation mark is a single character
     >]

Right, let's take a step backwards and see what we have achieved...In essence, what we've done is to completely separate the code that does the work (the annotator-fn) from the UIMA mechanics. We can now take any function that knows nothing about UIMA and, as long as we provide the pre/post fucntions, we can turn it into UIMA compatible. Anonymous functions will work as well...However, if you had a *derecord* that implements IFn and thus can act as a function, you would still need to wrap it in one more function. For example, imagine that our tokenizer was not a function but it did implement IFn which means it can act as a function. Something like this perhaps:

``` clojure

(defrecord RE-Tokenizer [regex]
 ITokenizer
 (tokenize [_ sentence] (re-seq regex sentence)) ;;does this implementation ring any bells? 
IComponent 
 (run [this sentence] 
   (if (string? sentence) (tokenize this sentence) 
       (map #(tokenize this %) sentence)))
 clojure.lang.IFn  ;;can act as an fn
  (invoke  [this arg] (tokenize this arg))
  (applyTo [this args] (apply tokenize this args))  )
     
```

In this case, the object does not have a nullary constructor, thus cannot be instantiated via

``` java 

     Class.forName("clojuima.core/RE-Tokenizer").newInstance();   
```     

The workaround is pretty simple though... just wrap it in a fn that simply calls it like so:

    (defn my-tok [regex s] 
      ((RE-Tokenizer. regex) s)) 

...and you're sorted! The function that wraps your type does have a nullary constructor so all is well :) 

We've come a long way indeed, but now we've got a rather good story with respect to proxy-ing UIMA components. We are not using Clojure's *proxy* but we've recovered its dynamic nature though our own UIMAProxy. We've also written some basic helper functions that helped us to keep our code clean and readable which is always important...

Ou next task will be significantly easier/shorter and will basically involve doing the exact opposite of what we just did! We're going to cover the case where you want to use UIMA components that other people have written (there are plenty), in your own Clojure code as regular functions without all the ceremony.
Fear not however - this is going to take less than 5 minutes :)

# UIMA -> Clojure

Right, let's assume that there is a UIMA component you really want to use in your code - the UIMA HMMTagger. We could have picked any one really...

Go back to your project.clj and add the following dependency coordinates:

    [org.apache.uima/Tagger "2.3.1"]
    [org.apache.uima/WhitespaceTokenizer "2.3.1"]

Now go to your *core* namespace and add these declarations to the *:import* list:

    [org.apache.uima.examples.tagger HMMTagger]
    [org.apache.uima TokenAnnotation SentenceAnnotation] 

dependencies sorted...

Now, recall for earlier that UIMA components do 3 distinct tasks in the .process() method (which is *void* and returns nothing). So, if we want a function that takes a sentence and returns the pos-tags, using the HMMTagger, we need the following:

* to write the entire sentence to the JCas  
* to call .process() on the HMMTagger supplying the JCas with the sentence
* to extract the annotations created by calling .process and shove them in a list.
* if you don't want use the default WhiteSpaceTokenizer, you have to explicitly supply the tokens whose indices have to be injected as TokenAnnotations in the CAS.

Observe the following function for 2 minutes: 
``` clojure

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
                 (mapv #(.getPosTag ^TokenAnnotation %) (select-annotations jc TokenAnnotation 0 (count s))))]
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

(def postag-brown (partial hmm-postag "/home/path/to/model/binary/BrownModel.dat"))

```

This does look scary but trust me it's not! Everything that happens in there you've already seen...The truth is that there is quite a bit of ceremony in order to be able to deal with both cases of *wanting the WhiteSpaceTokenizer* or not. The important bits are the following:


    (if need-aggregate?
       (produce :analysis-engine (-> "HmmTaggerAggregate.xml" clojure.java.io/resource xml-resource) config)
       (produce :analysis-engine (-> "HmmTagger.xml" clojure.java.io/resource xml-resource) config))

this is our HMMTagger...since there is a descriptor why not use it?

then we create a JCas from the analysis-engine we just produced:

    (jcas tagger)  ;;this is important as the annotation-types need to be registered appropriately

then we define a function that will do the actual tagging

``` clojure
(fn [^String s ^JCas jc]
   (.setDocumentText jc s)
   (.process tagger jc)  ;;notice that we use the tagger internally
   (mapv #(.getPosTag ^TokenAnnotation %) 
    (select-annotations jc TokenAnnotation 0 (count s))))
```

All that is left is to loop through the sentences...Notice how similar the 2 *reduce* statements are... The first one needs not to worry about injecting annotation indices because the WhitSpaceTokenizer takes care of that. The second however needs to reconstruct the indices from the tokens and the original sentence. This is where *calculate-indices* shines...

``` clojure
    (reduce-kv 
      (fn [init sentence tokens] 
        (conj init (do (inject-annotation! jc [SentenceAnnotation 0 (count sentence)])
                    (doseq [[_ [b e]] (calculate-indices sentence tokens)]
                      (inject-annotation! jc [TokenAnnotation b e])) 
                  (pos-tag sentence jc))))
      [] proper-map)
      
```

...and that is it! Extract one of the two models (located in the jar) somewhere on your file-system and we're ready to test our function.


     clojuima.core=> (postag-brown sample (re-seq token-regex sample) 3) 
     [["abn" "nns" "vb" "nn" "cc" "nn" "to" "vb" "."]]

You get back a 2d vector. The outer vector represents a collection of sentences and the inner one a collection of pos-tags. You could of course fall back to the WhiteSpaceTokenizer by simply not providing any tokens...

    clojuima.core=> (postag-brown sample nil 2)
    May 02, 2013 3:05:04 PM WhitespaceTokenizer initialize
    INFO: "Whitespace tokenizer successfully initialized"
    May 02, 2013 3:05:05 PM WhitespaceTokenizer typeSystemInit
    INFO: "Whitespace tokenizer typesystem initialized"
    May 02, 2013 3:05:05 PM WhitespaceTokenizer process
    INFO: "Whitespace tokenizer starts processing"
    May 02, 2013 3:05:05 PM WhitespaceTokenizer process
    INFO: "Whitespace tokenizer finished processing"
    [["abn" "nns" "vb" "nn" "cc" "nn" "to" "vb" "."]]  ;;same result for our little sample sentence

and this is how it's done! :) You could of course create a couple of protocols ITagger, ITrainable and implement a defrecord that is capable of not only tagging but also training. There are all sorts of things you can do actually...here I'm presenting the common case of just wanting to tag some text...there is a caveat in my above function with respect to performance...suppose you pass the following map of sentences to tokens: 

     {"My name is Jim."    ["My" "name" "is" "Jim" "."]
      "Your name is John." ["Your" "name" "is" "John" "."]
      "His name is Nick."  []  ;;empty seq or nil
     } 

even though you provided the tokens for the first 2 entries, they will be ignored completely because the last one is empty and thus the WhiteSpaceTokenizer is needed. Therefore, it is not suggested to do that, unless of course you don't mind or you manage to adjust it to your needs ;)

     
Well, that was a lot easier, wasn't it? We've finished...Both of our initial goals have been implemented! We can take Clojure functions and turn them into UIMA components without modification and vice versa... :)

Even though that wasn't an easy task, I hope you enjoyed working through it...You now have the basis to work easier with UIMA from Clojure. Moreover, feel free to experiment with the code shown here, especially if you're designing an API to interact with UIMA. The functions shown above are by no means a complete wrapper and perhaps can be improved further.

Finally, I think you deserve a drink... ;)

Hope that helps,

Dimitrios Piliouras ->[jimpil](https://github.com/jimpil) 

----
## thanks
*Richard Eckart de Castilho* (for being a UIMA/uimaFIT guru and for being patient with me)    
*Jens Haase*   (for knowing Clojure and for prototyping UIMAProxy.java)


## License

Copyright © 2013 Dimitrios Piliouras

Distributed under the [BSD](https://github.com/jimpil/clojuima/blob/master/license/licence.txt) license.

