exist-ocular-ocr
==================

Integrate the Ocular OCR library into eXist-db.

Demo and documentation are included in the package.

## Compile and install

1. clone the github repository: https://github.com/ljo/exist-ocular-ocr
2. edit local.build.properties and set exist.dir to point to your eXist install directory
3. call "ant" in the directory to create a .xar
4. upload the xar into eXist using the dashboard
5. optionally download precomputed models since in the minimal case Ocular itself requires 7 GB of RAM.

## Functions

There are four main steps, where one step maps to one function (except for `ocr:initialize-language-model-*` which can take strings and nodes, or a collection tree) and one optional:

```xquery
ocr:initialize-language-model-string($language-model, $text, $config)
ocr:initialize-language-model-collection($language-model, $collection, $qname, $config)
ocr:initialize-font($language-model, $font, $config)
```

Optionally generating a glyph substitution model:
```xquery
ocr:initialize-glyph-substitution-model($language-model, $init-gsm, $config)
ocr:train-font($language-model, $init-font, $input-path, $trained-font, $config)
ocr:transcribe($language-model,$trained-font, $input-path, $output-path, $retrained-language-model, $config)
```

Extended documentation can be found after installing the package.

Remember to match the initialized font height with the lineHeight of the training and transcribing. Ocular is not forgiving at all in this sense so mixing heights will definitely give you garbage. 

## Usage example

```xquery
xquery version "3.0";

import module namespace ocr="http://exist-db.org/xquery/ocular-ocr";
declare namespace tei="http://www.tei-c.org/ns/1.0";

let $do-run := (false(), false(), false(), true(), false())
let $lang := "swe"
let $lang-map := map {"deu": 1, "swe": 2, "ces": 3}
let $language-model-uri := (xs:anyURI("/db/apps/ocular-ocr/resources/models/deu.ser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/swe.dw.ser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/ces.ser")
)[xs:int($lang-map?($lang))]
let $retrained-language-model-uri := (xs:anyURI("/db/apps/ocular-ocr/resources/models/deu.trained.ser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/swe.dw.trained.ser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/ces.trained.ser")
)[xs:int($lang-map?($lang))]
let $init-font-uri := (xs:anyURI("/db/apps/ocular-ocr/resources/models/deu.fontser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/swe.dw.fontser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/ces.fontser")
)[xs:int($lang-map?($lang))]
let $trained-font-uri := (xs:anyURI("/db/apps/ocular-ocr/resources/models/deu.trained.fontser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/swe.dw.trained.fontser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/ces.trained.fontser")
)[xs:int($lang-map?($lang))]
let $init-gsm-uri := (xs:anyURI("/db/apps/ocular-ocr/resources/models/deu.gsmser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/swe.gsmser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/ces.gsmser")
)[xs:int($lang-map?($lang))]
let $trained-gsm-uri := (xs:anyURI("/db/apps/ocular-ocr/resources/models/deu.trained.gsmser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/swe.trained.gsmser"),
xs:anyURI("/db/apps/ocular-ocr/resources/models/ces.trained.gsmser")
)[xs:int($lang-map?($lang))]
let $input-path-uri := (xs:anyURI("/db/temp/ocular/deu/pdf"),
xs:anyURI("/db/temp/ocular/swe/png"),
xs:anyURI("/db/temp/ocular/ces/pdf")
)[xs:int($lang-map?($lang))]
let $output-path-uri := (xs:anyURI("/db/temp/ocular/deu/out"),
xs:anyURI("/db/temp/ocular/swe/out"),
xs:anyURI("/db/temp/ocular/ces/out")
)[xs:int($lang-map?($lang))]
let $extracted-lines-path-uri := (xs:anyURI("/db/temp/ocular/deu/out/lines"),
xs:anyURI("/db/temp/ocular/swe/out/lines"),
xs:anyURI("/db/temp/ocular/ces/out/lines")
)[xs:int($lang-map?($lang))]
let $text := (<p>Der Band enthält Engels’ Werke, Artikel, Reden und Entwürfe von März 1891 bis zu seinem Tode am 5. August 1895. Dabei handelt es sich um Einleitungen bzw. 16 Vor- und Nachworte zu Schriften von Marx und Engels, u. a. zu „Der Bürgerkrieg in Frankreich“, „Lohnarbeit und Kapital", „Die Entwicklung des Sozialismus von der Utopie zur Wissenschaft", „Der Ursprung der Familie, des Privateigentums und des Staats", „The condition of the working-class in England in 1844", „Manifest der Kommunistischen Partei", „Elend der Philosophie" und die „Einleitung zu Marx' ,Klassenkämpfe in Frankreich 1848 bis 1850'". In seiner letzten Schaffensperiode verfasste Engels außerdem verschiedenartige Abhandlungen, neben „Zur Kritik des sozialdemokratischen Programmentwurfs 1891" und „Die Bauernfrage in Frankreich und Deutschland" zählt dazu die berühmte Beitragsfolge „Kann Europa abrüsten?". Verschiedene Facetten seiner Rolle als Nestor der internationalen Sozialdemokratie dokumentieren weitere Schreiben, Gesprächsaufzeichnungen und mehrere von Engels redigierte Übersetzungen von eigenen und Marx’ Arbeiten durch dritte Personen. Hinzu kommen zwei der Ur- und Frühgeschichte gewidmete Studien: „Ein neuentdeckter Fall von Gruppenehe" sowie „Zur Geschichte des Urchristentums". Drei Manuskripte (Fragment du brouillon de l’article «Le socialisme en Allemagne», Deux fragments du manuscrpit relatifs à l’interview accordée à «L’Éclair» und To the Fabian Society) werden erstmals, drei weitere Dokumente erstmalig in der Sprache des Originals publiziert. Das Werk, dessen ersten Band ich dem Publikum übergebe, bildet die Fortsetzung meiner 1859 veröffentlichten Schrift: „Zur Kritik der poli-
tischen Oekonomie“. Die lange Pause zwischen Anfang und Fortsetzung ist einer langjährigen Krankheit geschuldet, die meine Arbeit wieder und wieder unterbrach. Der Inhalt jener früheren Schrift ist resümirt im ersten Kapitel dieses
Bandes. Es geschah dieß nicht nur des Zusammenhangs und der Voll-
ständigkeit wegen. Die Darstellung ist verbessert. Soweit es der Sachverhalt
irgendwie erlaubte, sind viele früher nur angedeutete Punkte hier weiter
entwickelt, während umgekehrt dort ausführlich Entwickeltes hier nur
angedeutet wird. Die Abschnitte über die Geschichte der Werth- und Geld-
theorie fallen jetzt natürlich ganz weg. Jedoch findet der Leser der früheren
Schrift in den Noten zum ersten Kapitel neue Quellen zur Geschichte jener
Theorie eröffnet. Aller Anfang ist schwer, gilt in jeder Wissenschaft. Das Verständniß des
ersten Kapitels, namentlich des Abschnitts, der die Analyse der Waare
enthält, wird daher die meiste Schwierigkeit machen. Was nun näher die
Analyse der Werthsubstanz und der Werthgröße betrifft, so habe
ich sie möglichst popularisirt. Anders mit der Analyse der Werthform.</p>, 
   <p>När det gäller Åke och hans värld kommer vi långt ifrån bank- och finansväsendet. 
      Mimmi och Sonja är i fjällen på semester när lavinen går på Blåsjöfjället.  
      Skärgårdens verklighet en vinterdag är inte heller så rosenskimrande.  
      Herr Arne är dock i nya världen för gull och penningar.  
      Ute på ön är oron stor för Olagus ursinne.</p>,
   <p>Úloha Petra Gedy a rybáře Tole Persona. Od té doby, kde Hjelm obdržel 
      nestoudný a drzý list Holtův, uplynulo osm dní.
      „Dobře, tatínku; nemohu tomu brániti", odpovídala Mája hlasem, 
      který se poněkud uchyloval od obyčejné její klidnosti.</p>)[xs:int($lang-map?($lang))]
let $config := 
    <parameters>
        <!-- language model parameters -->
        <!-- default: false -->
        {if ($lang eq "deu") then <param name="insertLongS" value="true"/> else ()}
        <!-- default: 0 -->
        <!--param name="lmCharCount" value="0"/-->
        <!-- default: 6 (char ngram) -->
        <!--param name="charN" value="5"/-->
        <!-- default: 4.0 -->
        <!--param name="lmPower" value="4.0"/-->
        <!-- font initialization parameters -->
        <!-- default: null, use all fonts -->
        {if ($lang eq "deu") then <param name="allowedFontsPath" value="/db/apps/ocular-ocr/resources/init-font/blackletter-fonts.txt"/> 
         else if ($lang eq "swe") then <param name="allowedFontsPath" value="/db/apps/ocular-ocr/resources/init-font/serif-fonts.txt"/>
         else ()}
        <!-- default: 8 -->
        <param name="numFontInitThreads" value="8"/>
        <!-- train-font and transcribe parameters -->
        <!-- default: 0.12
             demo documents deu: 0.11 swe: 0.05?,
             not more since (swe) images will be all black then -->
        {if ($lang eq "deu") then <param name="binarizeThreshold" value="0.11"/> else ()}
        {if ($lang eq "swe") then <param name="binarizeThreshold" value="0.05"/> else ()}
        <!-- default: true -->
        {if ($lang eq "deu") then <param name="uniformLineHeight" value="true"/>
         else if ($lang eq "swe") then <param name="uniformLineHeight" value="true"/> 
         else ()}
        <!-- default: 30 the height in pixels to scale all extracted lines to.
             this is also used for the font glyph height.
             demo documents deu: 108? swe: 35/38-50? -->
        {if ($lang eq "deu") then <param name="lineHeight" value="60"/>
         else if ($lang eq "swe") then <param name="lineHeight" value="38"/> 
         else ()}
        <!-- default: 30, the estimated average height of the lines in pixels in
             the source documents. If you see extracted lines split in strange ways
             you should probably change this to a higher value (measure in source docs)
             NB! feature not available in ocular itself. See demo documents deu: 108 swe: 51/61? -->
         {if ($lang eq "deu") then <param name="extractLineHeight" value="108"/>
         else if ($lang eq "swe") then <param name="extractLineHeight" value="51"/> 
         else ()}
        <!-- default: true, demo documents deu: true swe:false -->
        <param name="crop" value="false"/>
        <!-- defaut: null = no saved line images -->
        <param name="extractedLinesPath" value="{$extracted-lines-path-uri}"/>
        <!-- default: 5 (between 2-6 seems best, but ymmv)-->
        <param name="numExtractIterations" value="1"/>
           
        <!-- default: 100 (could probably go much lower, like 30-50) -->
        {if ($lang eq "deu") then <param name="numExtractRestarts" value="55"/> 
         else if ($lang eq "swe") then <param name="numExtractRestarts" value="45"/> 
         else ()}
        <!-- default: false -->
        <param name="markovVerticalOffset" value="false"/>
        <!-- default: 10 (10-50) -->
        <param name="beamSize" value="40"/>
        <!-- default: 3 -->
        <param name="numEMIters" value="3"/>
        <!-- default: DEFAULT -->
        <!--param name="emissionEngine" value="OPENCL"/-->
        !-- default: 8 -->
        <param name="numMstepThreads" value="8"/>
        !-- default: 1 -->
        <param name="numDecodeThreads" value="1"/>
        <!-- default: 32 -->
        <param name="decodeBatchSize" value="16"/>
        <!-- default: MAX_INT or -1 -->
        <!--param name="numDocs" value="1"/-->
        <!-- default: false -->
        <param name="allowGlyphSubstitution" value="false"/>
        <!-- default: null (for train-font and transcribe) -->
        <!--param name="inputGsmPath" value="{$init-gsm-uri}"/-->
        <!-- default: null (for train-font and transcribe) -->
        <!--param name="outputGsmPath" value="{$trained-gsm-uri}"/-->
        <!-- default: 0 -->
        {if ($lang eq "deu") then <param name="numDocsToSkip" value="14"/> else ()}
        {if ($lang eq "swe") then <param name="numDocsToSkip" value="7"/> else ()}
        <!-- default: MAX_INT -->
        <param name="updateDocBatchSize" value="1"/>
    </parameters>
let $res-init-language-model := 
    if ($do-run[1]) then 
        if ($lang eq "swe") then
            ocr:initialize-language-model-collection($language-model-uri, 
            xs:anyURI("/db/temp/ocular/swe/lm-data" (: ("/db/dramawebben/data/works/StrindbergA_MasterOlof" LofvingC_SaVannHanStoltsJungfrun 
            "/db/temp/ocular/swe/lm-data" :)), xs:QName("tei:p"), $config)
        else 
            ocr:initialize-language-model-string($language-model-uri, $text, $config)
    else ()
let $res-init-font := 
    if ($do-run[2]) then
        ocr:initialize-font($language-model-uri, $init-font-uri, $config)
    else ()
let $res-init-gsm := 
    if ($do-run[3]) then
        ocr:initialize-glyph-substitution-model($language-model-uri, $init-gsm-uri, $config)
    else () 
let $res-train-font := 
    if ($do-run[4]) then 
        ocr:train-font($language-model-uri, $init-font-uri, $input-path-uri, $output-path-uri, $trained-font-uri, $config)
    else ()
let $res-transcribe := 
    if ($do-run[5]) then
        ocr:transcribe($language-model-uri, $trained-font-uri, $input-path-uri, $output-path-uri,
                   $retrained-language-model-uri, $config)
    else ()
return ($res-init-language-model, $res-init-font, $res-init-gsm, $res-train-font, $res-transcribe)
```
