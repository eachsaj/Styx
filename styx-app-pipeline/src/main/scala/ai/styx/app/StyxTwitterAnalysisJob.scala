package ai.styx.app

import java.net.URL

import ai.styx.common.Logging
import ai.styx.domain.events.{Trend, Tweet, WordCount}
import ai.styx.usecases.twitter.{TrendsWindowFunction, TweetTimestampAndWatermarkGenerator, WordCountWindowFunction}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time
import org.joda.time.{DateTime, Period}

object StyxTwitterAnalysisJob extends App with Logging {
  // configuration
  val dataSourcePath = "/data/sample.json"
  val minimumWordLength = 5
  val wordsToIgnore = Array("would", "could", "should", "sometimes", "maybe", "perhaps", "nothing", "please", "today", "twitter", "everyone", "people", "think", "where", "about", "still", "youre", "photo", "movie")
  // setting this in seconds gives flexibility, shortest window is 1 second
  val evaluationPeriodInSeconds = 60 * 60 * 24 // 1 day
  val topN = 5
  val dateTimePattern = "yyyy-MM-dd HH:mm:sss"

  LOG.info("Start!")

  // set up Flink
  val startTime = DateTime.now
  val env = StreamExecutionEnvironment.getExecutionEnvironment
  env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

  // load the data
  val path = getClass.getResource(dataSourcePath)

  ///// part 1: count the words per day /////
  val wordsStream: DataStream[WordCount] = wordCount(env, path, minimumWordLength, evaluationPeriodInSeconds, wordsToIgnore)

  ///// part 2: look at 2 periods (e.g. days) and calculate slope, find top 5 /////
  trendsAnalysis(wordsStream, evaluationPeriodInSeconds, topN, dateTimePattern)

  env.execute("Twitter trends")

  LOG.info("Done!")
  LOG.info(s"Finished in ${new Period(startTime.getMillis, DateTime.now.getMillis).getSeconds} seconds")

  private def wordCount(env: StreamExecutionEnvironment, path: URL, minWordL: Int, seconds: Int, ignore: Array[String]): DataStream[WordCount] = {
    val mapper: ObjectMapper = new ObjectMapper()
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    mapper.configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)
    mapper.configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)

    implicit val typeInfo1 = TypeInformation.of(classOf[Tweet])
    implicit val typeInfo2 = TypeInformation.of(classOf[Option[Tweet]])
    implicit val typeInfo3 = TypeInformation.of(classOf[Option[String]])
    implicit val typeInfo4 = TypeInformation.of(classOf[String])
    implicit val typeInfo5 = TypeInformation.of(classOf[(String, Int)])
    implicit val typeInfo6 = TypeInformation.of(classOf[WordCount])

    env.readTextFile(path.getPath)
      // parse json
      .map(line => parse(line, mapper)).filter(_.isDefined).map(_.get).name("Parsing JSON string")
      // set event timestamp
      .assignTimestampsAndWatermarks(new TweetTimestampAndWatermarkGenerator).name("Getting event time")
      .flatMap(_.messageText
        // remove special characters & new lines, and convert to lower case
        .replaceAll("[~!@#$^%&*\\\\(\\\\)_+={}\\\\[\\\\]|;:\\\"'<,>.?`/\\n\\\\\\\\-]", "").toLowerCase()
        // create words
        .split("[ \\t]+")).name("Creating word list")
      .filter(word => !ignore.contains(word) && word.length >= minWordL)
      .map(s => Tuple2(s, 1)).name("Creating tuples")
      // group by word
      .keyBy(_._1)
      // group by period
      .timeWindow(Time.seconds(seconds))
      // count the words per day
      .apply(new WordCountWindowFunction()).name("Counting words")
  }

  private def trendsAnalysis(wordsStream: DataStream[WordCount], seconds: Int, topN: Int, dtPattern: String) = {
    implicit val typeInfo1 = TypeInformation.of(classOf[Map[String, List[Trend]]])

    wordsStream
      .windowAll(TumblingEventTimeWindows.of(Time.seconds(seconds * 2))) // when moving to stream processing: switch to sliding window
      .apply(new TrendsWindowFunction(seconds, dtPattern, topN)).name("Calculating trends")
      .addSink(x => x.foreach { trendPerPeriod =>
        print(trendPerPeriod)
      }).name("Printing results")
  }

  private def parse(line: String, mapper: ObjectMapper): Option[Tweet] = {
    val tweet = mapper.readValue(line, classOf[Tweet])
    if (tweet == null || tweet.messageText == null || tweet.creationDate == null) None else Some(tweet)
  }

  private def print(trendPerPeriod: (String, List[Trend])): Unit = {
    println("### Trending topics of " + trendPerPeriod._1)
    for (i <- trendPerPeriod._2.indices) {
      val trend = trendPerPeriod._2(i)
      println(s" ${i + 1}: ${trend.word.toUpperCase()}, slope=${trend.slope}")
    }
  }
}