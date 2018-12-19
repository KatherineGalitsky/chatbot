package opennlp.tools.doc2dialogue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.clulab.discourse.rstparser.DiscourseTree;

import opennlp.tools.chatbot.ChatBotCacheSerializer;
import opennlp.tools.chatbot.ChatIterationResult;
import opennlp.tools.parse_thicket.ParseTreeNode;
import opennlp.tools.parse_thicket.external_rst.MatcherExternalRST;
import opennlp.tools.parse_thicket.external_rst.PT2ThicketPhraseBuilderExtrnlRST;
import opennlp.tools.parse_thicket.external_rst.ParseCorefBuilderWithNERandRST;
import opennlp.tools.parse_thicket.external_rst.ParseThicketWithDiscourseTree;
import opennlp.tools.similarity.apps.BingQueryRunner;
import opennlp.tools.similarity.apps.HitBase;
import opennlp.tools.similarity.apps.utils.StringDistanceMeasurer;
import opennlp.tools.textsimilarity.ParseTreeChunk;
import opennlp.tools.textsimilarity.ParseTreeChunkListScorer;

public class Doc2DialogueBuilder {
	protected MatcherExternalRST matcher = new MatcherExternalRST();
	protected ParseCorefBuilderWithNERandRST ptBuilder = matcher.ptBuilderRST;
	protected PT2ThicketPhraseBuilderExtrnlRST phraseBuilder = matcher.phraseBuilder;
	protected Set<String> dupes = new HashSet<String> ();
	protected BingQueryRunner brunner = new BingQueryRunner();
	protected ParseTreeChunkListScorer scorer = new ParseTreeChunkListScorer();

	//private Map<String, List<HitBase>> query_listOfSearchResults = new HashMap<String, List<HitBase>>();
	private Map<String, String> queryConfirmed = new HashMap<String, String>();
	private BingCacheSerializer serializer = new BingCacheSerializer();
	private StringDistanceMeasurer meas = new StringDistanceMeasurer();

	public Doc2DialogueBuilder(){

		//query_listOfSearchResults = (Map<String, List<HitBase>>) serializer.readObject();
		queryConfirmed =  (Map<String, String>)serializer.readObject();
		if (queryConfirmed ==null) 
			queryConfirmed = new HashMap<String, String>();

	}

	public List<String> buildDialogueFromParagraph(String text){
		ParseThicketWithDiscourseTree pt = ptBuilder.buildParseThicket(text);
		List<List<ParseTreeNode>> phrs = phraseBuilder.buildPT2ptPhrases(pt);
		pt.setPhrases(phrs);

		DiscourseTree dt = pt.getDt();
		String dtLinearStr = dt.toString();
		//String[] dtLinearArr = dtLinearStr.split("\n");
		List<String> EDUs = new ArrayList<String>();

		String dump = dt.toString(true, true);
		String[] eduStrArrs = StringUtils.substringsBetween(dump, "TEXT:","\n");
		// TODO: now we parse dump, instead should get data directly
		String[] sectionsForNucleus = dump.split("\n");
		for(String sect : sectionsForNucleus){
			if (sect!=null && sect.length()>3){
				String nucleus = StringUtils.substringBetween(sect, "TEXT:","\n");
				if (nucleus!=null && isAcceptableEDU(nucleus))
					EDUs.add(nucleus);
			}
		}
		System.out.println(EDUs);
		List<List<ParseTreeNode>> phrases = new ArrayList<List<ParseTreeNode>>();


		formEduPhrases(dt,  phrases,  pt, "phrases" );

		List<List<ParseTreeNode>> shuffledPhrases = shuffleEduPhrasesWithInsertedQuestions(phrases);

		for(List<ParseTreeNode>p: shuffledPhrases){
			System.out.println(ParseTreeNode.toWordString(p));
		}

		return null;
	}

	private List<List<ParseTreeNode>> shuffleEduPhrasesWithInsertedQuestions(List<List<ParseTreeNode>> phrases) {
		int[] periods = new int[phrases.size()], questions = new int[phrases.size()]; 
		for(int i = 0; i<phrases.size(); i++ ){
			if (phrases.get(i).get(phrases.get(i).size()-1).getWord().startsWith("."))
				periods[i] = 1;
			else 
				periods[i] = 0;

			if (phrases.get(i).get(phrases.get(i).size()-1).getWord().startsWith("?"))
				questions[i] = 1;
			else 
				questions[i] = 0;
		}


		List<List<ParseTreeNode>> phrasesNew = new ArrayList<List<ParseTreeNode>>(phrases);
		int currQuestion = -1;
		for(int i = phrases.size()-1; i>=0; i-- ){
			if (questions[i] == 1){
				currQuestion = i;
			} else  if (periods[i] == 1 && (currQuestion >=0  || i ==0) && i< currQuestion-1 ) {
				List<ParseTreeNode> p = phrasesNew.get(currQuestion);
				phrasesNew.remove(currQuestion);
				phrasesNew.add(i+1, p);
				currQuestion = -1; 
			}
		}
		return phrasesNew;
	}

	private void formEduPhrases(DiscourseTree dt, List<List<ParseTreeNode>> phrases,  ParseThicketWithDiscourseTree pt, String typeAbove   ) {
		List<List<ParseTreeNode>> nodesThicket = pt.getNodesThicket();
		Map<Integer, List<List<ParseTreeNode>>> sentNumPhrases = pt.getSentNumPhrases();
		if (dt.isTerminal()) {
			List<ParseTreeNode> phraseEdu = new ArrayList<ParseTreeNode>();

			try {
				for(int i = dt.firstToken().copy$default$2(); i<=dt.lastToken().copy$default$2(); i++){
					phraseEdu.add(nodesThicket.get(dt.lastSentence()).get(i)); 
				}
				//System.out.println(typeAbove);
				if (typeAbove.equals("Satellite")){
					List<ParseTreeNode> phraseQuestionAdd = new ArrayList<ParseTreeNode>();
					//phraseQuestionAdd.add(new ParseTreeNode(" - ", "", null, null));

					List<ParseTreeNode> bestPhrase = tryToFindBestPhrase(sentNumPhrases.get(dt.lastSentence()));
					if (bestPhrase != null) {
						if (dupes.contains(bestPhrase.toString())){
							bestPhrase = null;
						} else 
							dupes.add(bestPhrase.toString());
					}
					if (bestPhrase==null) {
						/*	bestPhrase= phraseEdu;
						phraseAdd.add(new ParseTreeNode( "What", "QQ", null, 0));
						for(ParseTreeNode n: phraseEdu){
							if (n.getPos().equals("RB")){
								continue;
							}

							if (n.getWord().equals("I") || n.getWord().equals("me")){
								phraseAdd.add(new ParseTreeNode( "you", "PRP", null, 0));
								continue;
							}
							if (n.getWord().equals("my")){
								phraseAdd.add(new ParseTreeNode( "your", "PRP", null, 0));
								continue;
							}
							if (n.getPos().equals("VBN")){
								phraseAdd.add(n);
								break;
							}
							if (n.getPos().startsWith("NN")){
								phraseAdd.add(n);
								break;
							}

						} */
					} else {
						for(ParseTreeNode n: bestPhrase){
							if (n.getPos().equals("RB")){
								continue;
							}
							if (n.getWord().equals("I") || n.getWord().equals("me")){
								phraseQuestionAdd.add(new ParseTreeNode( "you", "PRP", null, 0));
								continue;
							}
							if (n.getWord().equals("my")){
								phraseQuestionAdd.add(new ParseTreeNode( "your", "PRP", null, 0));
								continue;
							}
							// default
							phraseQuestionAdd.add(n);
						}
						phraseQuestionAdd.add(new ParseTreeNode( "?", "QQ", null, 0));

						//System.out.println(ParseTreeNode.toWordString(phraseQuestionAdd));

						//System.out.println( sentNumPhrases.get(dt.lastSentence()));

						List<ParseTreeNode> minedQPhrase = correctQuestionViaWebMining(phraseQuestionAdd);
						if (minedQPhrase!=null && !minedQPhrase.isEmpty())
							phrases.add(minedQPhrase);
						else 
							phrases.add(phraseQuestionAdd);
					}			
				}
				phrases.add(phraseEdu);
				//System.out.println(ParseTreeNode.toWordString(phraseEdu));
				//System.out.println(phraseEdu+"\n");
			} catch (Exception e) {
				e.printStackTrace();
			}

			return;
		} else {
			DiscourseTree[] kids = dt.children();

			if (kids != null) {
				int order =0;
				for (DiscourseTree kid : kids) {
					String type = "Nucleus";
					if  ((order==0 && dt.relationDirection().toString().startsWith("Right") ||
							( order==1 && dt.relationDirection().toString().startsWith("Left"))))
						type = "Satellite";
					formEduPhrases(kid, phrases, pt, type);
					order++;
				}
			}
			return;
		}
	}

	private List<ParseTreeNode> tryToFindBestPhrase(List<List<ParseTreeNode>> list) {
		List<ParseTreeNode> results = new ArrayList<ParseTreeNode>();

		for( List<ParseTreeNode> ps: list){
			if (ps.get(0).getPhraseType().startsWith("WH") && ps.size()>2 )
				return ps;
			//'what':WP
			//[<10>SBAR'what':WP, <11>SBAR'I':PRP, <12>SBAR'like':VBP, 
			//<13>SBAR'to':TO, <14>SBAR'write':VB],
			if (ps.get(0).getPos().startsWith("WH") && ps.size()> 3 )
				return ps;

			//[<4>SBAR'what':WP, <5>SBAR'he':PRP, <6>SBAR'argues':VBZ]
			if (ps.get(0).getPos().startsWith("WP") && ps.size()>3 && ps.size()<10)
				return ps;
			//<36>VP'to':TO
			if (ps.get(0).getPos().equals("TO") && ps.size()>3 && ps.size()< 8){

				results.add(new ParseTreeNode("Do", "", null, null));
				results.add(new ParseTreeNode("you", "", null, null));
				results.add(new ParseTreeNode("want", "", null, null));
				results.addAll(ps); 
				return results;
			}
			//[<12>VP'being':VBG, <13>VP'responsible':JJ, <14>VP'for':IN, <15>VP'shooting':VBG, <16>VP'down':RB, <17>VP'plane':NN]
			if (ps.get(0).getPos().equals("VBG") && ps.size()>2 && ps.size()< 8){

				results.add(new ParseTreeNode("Who", "", null, null));
				results.add(new ParseTreeNode("is", "", null, null));
				ps.remove(0);
				results.addAll(ps); 
				return results;
			}
			//<1>PP'At':IN, <2>PP'the':DT, <3>PP'beginning':NN
			if (ps.get(0).getPos().equals("IN") && ps.size()>2 && ps.get(1).getPos().equals("DT")  && ps.size()<= 8){

				results.add(new ParseTreeNode("What", "", null, null));
				results.add(new ParseTreeNode("happens", "", null, null));

				results.addAll(ps); 
				return results;
			}
			//<3>SBARQ'it':PRP, <4>SBARQ'been':VBN, <5>SBARQ'so':RB, <6>SBARQ'difficult':JJ, <7>SBARQ'?':.],		
			if (ps.get(0).getPos().equals("PRP") && ps.size()>3 && ps.get(1).getPos().equals("VBN") &&   ps.size()<= 8){
				results.add(new ParseTreeNode("Why", "", null, null));
				results.addAll(ps); 
				return results;
			}

			//[<13>PP'of':IN, <14>PP'synaptic':JJ, <15>PP'connections':NNS, <16>PP'or':CC, <17>PP'field':NN, <18>PP'properties':NNS]
			if (ps.get(0).getPos().equals("IN") && ps.size()>3 && ps.get(1).getPos().equals("JJ") &&  ps.size()<= 8){
				results.add(new ParseTreeNode("How", "", null, null));
				results.add(new ParseTreeNode("about", "", null, null));
				results.addAll(ps); 
				return results;
			}
		}

		if (results.isEmpty()){
			for( List<ParseTreeNode> ps: list){
				if (ps.get(0).getPos().equals("VBP") && ps.size()>2 && ps.size()< 8){
					results.add(new ParseTreeNode("What", "", null, null));
					results.add(new ParseTreeNode("do", "", null, null));
					results.add(new ParseTreeNode("you", "", null, null));
					results.addAll(ps);
					return results;
				}
				if (ps.get(0).getPos().equals("VBD") && ps.size()>2 && ps.size()< 8){
					results.add(new ParseTreeNode("Who", "", null, null));
					results.addAll(ps);
					return results;
				}
				//<3>VP'is':VBZ, <4>VP'mostly':RB, <5>VP'interested':JJ
				if (ps.get(0).getPos().equals("VBZ") && ps.size()>2 && ( ps.get(1).getPos().equals("RB") ||  ps.get(1).getPos().equals("JJ") ) && ps.size()< 8){
					results.add(new ParseTreeNode("Who", "", null, null));
					results.addAll(ps);
					return results;
				}

				//[<5>NP'the':DT, <6>NP'White':NNP, <7>NP'House':NNP]
				if (ps.get(0).getPos().equals("DT") && ps.size()>2 &&  ps.get(1).getPos().equals("NNP")  
						&& ps.size()< 8 && ps.get(0).getPhraseType().startsWith("NP")){
					results.add(new ParseTreeNode("What", "", null, null));
					results.add(new ParseTreeNode("about", "", null, null));
					results.addAll(ps);
					return results;	
				}
			}
		}
		return null;
	}

	private void formEduPhrasesEdusOnly(DiscourseTree dt, List<List<ParseTreeNode>> phrases,  List<List<ParseTreeNode>> nodesThicket, String typeAbove   ) {
		if (dt.isTerminal()) {
			List<ParseTreeNode> phraseEdu = new ArrayList<ParseTreeNode>();


			try {
				for(int i = dt.firstToken().copy$default$2(); i<=dt.lastToken().copy$default$2(); i++){
					phraseEdu.add(nodesThicket.get(dt.lastSentence()).get(i)); 
				}
				System.out.println(typeAbove);
				if (typeAbove.equals("Satellite")){
					List<ParseTreeNode> phraseAdd = new ArrayList<ParseTreeNode>(), newPhraseEdu = new ArrayList<ParseTreeNode>();
					phraseAdd.add(new ParseTreeNode( "What", "QQ", null, 0));

					for(ParseTreeNode n: phraseEdu){
						if (n.getWord().equals("I") || n.getWord().equals("me")){
							phraseAdd.add(new ParseTreeNode( "you", "PRP", null, 0));
							continue;
						}
						if (n.getWord().equals("my")){
							phraseAdd.add(new ParseTreeNode( "your", "PRP", null, 0));
							continue;
						}
						if (n.getPos().equals("VBN")){
							phraseAdd.add(n);
							break;
						}
						if (n.getPos().startsWith("NN")){
							phraseAdd.add(n);
							break;
						}

					}



					phrases.add(phraseAdd);
				}
				phrases.add(phraseEdu);
			} catch (Exception e) {
				e.printStackTrace();
			}

			return;
		} else {
			DiscourseTree[] kids = dt.children();

			if (kids != null) {
				int order =0;
				for (DiscourseTree kid : kids) {
					String type = "Nucleus";
					if  ((order==0 && dt.relationDirection().toString().startsWith("Right") ||
							(	order==1 && dt.relationDirection().toString().startsWith("Left"))))
						type = "Satellite";
					formEduPhrasesEdusOnly(kid, phrases, nodesThicket, type);
					order++;
				}
			}
			return;
		}
	}

	private void formEduPhrasesOLd(DiscourseTree dt, List<List<ParseTreeNode>> phrases,  List<List<ParseTreeNode>> nodesThicket  ) {
		if (dt.isTerminal()) {
			return;
		} else {

			List<ParseTreeNode> phraseFrom = new ArrayList<ParseTreeNode>(),  phraseTo = new ArrayList<ParseTreeNode>();

			for(int i = dt.firstToken().copy$default$1(); i<=dt.lastToken().copy$default$1(); i++){
				phraseFrom.add(nodesThicket.get(dt.firstSentence()).get(i)); 
			}

			for(int i = dt.firstToken().copy$default$2(); i<=dt.lastToken().copy$default$2(); i++){
				phraseTo.add(nodesThicket.get(dt.lastSentence()).get(i)); 
			}

			//String lemmaFrom = nodesThicket.get(dt.firstSentence()).get(dt.firstToken().copy$default$2()).getWord();
			//String lemmaTo = nodesThicket.get(dt.lastSentence()).get(dt.lastToken().copy$default$2()-1).getWord();


			phrases.add(phraseFrom);
			phrases.add(phraseTo);

			DiscourseTree[] kids = dt.children();
			if (kids != null) {
				for (DiscourseTree kid : kids) {
					formEduPhrasesOLd(kid, phrases, nodesThicket);
				}
			}
			return;
		}
	}

	private boolean isAcceptableEDU(String nucleus) {
		//if (nucleus.length()> 10 && nucleus.split(" ").length>1)
		return true;
		//return false;
	}


	public List<ParseTreeNode>  correctQuestionViaWebMining(List<ParseTreeNode> questionPhrase){
		if (questionPhrase.size()<4)
			return null;

		String origQuestion = ParseTreeNode.toWordString(questionPhrase);
		List<ParseTreeNode> questionResult = new ArrayList<ParseTreeNode>();

		String foundInCache = this.queryConfirmed.get(origQuestion);
		if (foundInCache != null && foundInCache.length()>3){
			String[] bestExtrQsplit =  foundInCache.split(" ");
			for(String w: bestExtrQsplit){
				questionResult.add(new ParseTreeNode(w, "", null, null));
			}
			return questionResult;
		}

		List<HitBase> searchRes = brunner.runSearch(origQuestion);

		double maxScore = -1; String bestExtrQ = null;

		for(HitBase h: searchRes){
			String extractedQuestion = extractQuestionFromSearchResultTitle(h.getTitle());
			if (extractedQuestion.split(" ").length*1.5 < origQuestion.split(" ").length)
				continue;

			List<List<ParseTreeChunk>> res = matcher.assessRelevanceCache(origQuestion, extractedQuestion);
			double score = //meas.measureStringDistance(origQuestion, extractedQuestion);
					scorer.getParseTreeChunkListScore(res);
			if (score>maxScore){
				maxScore = score;
				bestExtrQ = extractedQuestion;
			}
		}
		if (maxScore<1.5){ //0.6)
			this.queryConfirmed.put(origQuestion,  "" );
			try {
				serializer.writeObject(queryConfirmed);
			} catch (Exception e) {
				System.err.println("Error serializing");
			}
			return null;
		}
		if (!bestExtrQ.endsWith("?"))
			bestExtrQ+=" ?";
		String[] bestExtrQsplit =  bestExtrQ.split(" ");
		for(String w: bestExtrQsplit){
			questionResult.add(new ParseTreeNode(w, "", null, null));
		}

		this.queryConfirmed.put(origQuestion,  bestExtrQ );
		// each time new query comes => write the results
		try {
			serializer.writeObject(queryConfirmed);
		} catch (Exception e) {
			System.err.println("Error serializing");
		}

		return questionResult;
	}

	// query: what do you not like to do today?
	// result: 
	//What Do You Like To Do? Song | Hobbies Song for Kids

	//How to deal with people you don't like - Business Insider
	private String extractQuestionFromSearchResultTitle(String title) {
		int indexEnd = title.indexOf('?');
		if (indexEnd<0)
			indexEnd = title.indexOf('-');
		if (indexEnd<0)
			indexEnd = title.indexOf(':');
		if (indexEnd<0)
			indexEnd = title.indexOf('|');
		if (indexEnd<0)
			indexEnd = title.indexOf('/');
		if (indexEnd<0)
			indexEnd = title.indexOf('.');
		if (indexEnd<0)
			indexEnd = title.indexOf('(');

		if (indexEnd>0)
			return title.substring(0, indexEnd);


		return title;
	}



	public static void main(String[] args){
		Doc2DialogueBuilder builder = new Doc2DialogueBuilder();

		String texts[] = new String[]{

		"I thought I d tell you a little about what I like to write. And I like to immerse myself in my topics. I just like to dive right in and become sort of a human guinea pig. And I see my life as a series of experiments. So , I work for Esquire magazine , and a couple of years ago I wrote an article called  My Outsourced Life ,  where I hired a team of people in Bangalore , India , to live my life for me. "
						+ "So they answered my emails. They answered my phone. ",
		"Dutch accident investigators say that evidence points to pro-Russian rebels as being responsible for shooting down plane. The report indicates where the missile was fired from and identifies who was in control of the territory and pins the downing of the plane on the pro-Russian rebels. "+
								"However, the Investigative Committee of the Russian Federation believes that the plane was hit by a missile from the air which was not produced in Russia. "+
								"At the same time, rebels deny that they controlled the territory from which the missile was supposedly fired."

		,"I want to start with a very basic question. At the beginning of AI, people were extremely optimistic about the field's progress, but it hasn't turned out that way. Why has it been so difficult? If you ask neuroscientists why understanding the brain is so difficult, they give you very intellectually unsatisfying answers, "
				+ "like that the brain has billions of cells, and we can't record from all of them, and so on.",
				"It turns out the ants do pretty complicated things, like path integration, for example. If you look at bees, bee navigation involves quite complicated computations, involving position of the sun, and so on and so forth. But in general what he argues is that if you take a look at animal cognition, human too, it's computational systems. Therefore, you want to look the units of computation. "
						+ "Think about a Turing machine, say, which is the simplest form of computation, you have to find units that have properties like read, write, and address."
						+ " That's the minimal computational unit, so you got to look in the brain for those. You're never going to find them if you look for strengthening of synaptic connections or field properties, and so on. You've got to start by looking for what's there and what's working and you see that from Marr's highest level.",

		"Well, like strengthening synaptic connections. Scientists have been arguing for years that if you want to study the brain properly you should begin"
								+ " by asking what tasks is it performing. So he is mostly interested in insects. So if you want to study, say, the neurology of an ant, you ask what does the ant do? ",

		"As the Trump administration scrambles to find a replacement for outgoing advisor John Kelly, officials announced Monday that a high-level White House ficus would leave for the State Arboretum of Virginia after declining the president’s offer to be chief of staff. "
										+ "The ficus has been honored to serve President Trump and the American people these last several months and plans to continue advancing the MAGA cause as a member of the private sector, read a statement drafted by an aide for the ficus, noting that the potted shrub was one of the longest-tenured and most-trusted members of the Trump administration, spending countless hours working alongside the president from a sunny spot inside the Oval Office.",

		"Rumors that the ficus was forced out following a heated argument with Jared Kushner are simply untrue. The ficus will spend the next few weeks helping with the transition of its replacement, a large fern, before departing to work in the tropical plant section of the arboretum. At press time, the White House was reportedly thrown into chaos after the large fern confirmed it would not accept the new job." };

		for(String t: texts){
			builder.buildDialogueFromParagraph(t); 
			System.out.println("\n\n");
		}



	}
}
