package org.apache.hadoop.mapreduce.approx.app;

import java.lang.Exception;
import java.io.IOException;
import java.util.Iterator;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.IntSumReducer;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.CounterGroup;
import org.apache.hadoop.mapreduce.Counter;


import org.apache.hadoop.mapreduce.approx.ApproximatePartitioner;
import org.apache.hadoop.mapreduce.approx.ApproximateMapper;
import org.apache.hadoop.mapreduce.approx.ApproximateReducer;
import org.apache.hadoop.mapreduce.approx.lib.input.MySampleTextInputFormat;

import org.apache.log4j.Logger;

import org.json.simple.JSONObject;
//import org.json.simple.parser.JSONParser;
import org.json.simple.parser.*;

public class GitHubEvent {
	/**
	 * Launch wikipedia page rank.
	 */
	public static class GitHubEventMapper extends ApproximateMapper<LongWritable, Text, Text, DoubleWritable> {
		private static final Logger LOG = Logger.getLogger("Subset.AppMapper");

		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
			JSONParser parser = new JSONParser();
			JSONObject line = null;
			try{
				line = (JSONObject)parser.parse(value.toString());
			} catch (org.json.simple.parser.ParseException e){
				e.printStackTrace();
			}
			String keyword = (String)line.get("type");
			String filter = context.getConfiguration().get("map.input.filter", "PushEvent");
			if(keyword.equals(filter)){
				Long size =  (Long)((JSONObject)line.get("payload")).get("size");
				DoubleWritable quantity = new DoubleWritable((double)(size.longValue()));
				//LOG.info("App key:" + keyword);
			// if more than one keyword, concatenate using "+*+" 
				context.write(new Text(filter), quantity);
			}
			
		}
	}
	public static class GitHubEventReducer extends ApproximateReducer<Text, DoubleWritable, Text, DoubleWritable> {
		DoubleWritable result = new DoubleWritable();
		public void reduce(Text key, Iterable<DoubleWritable> values, Context context) throws IOException, InterruptedException {
			double sum = 0;
			for(DoubleWritable val : values){
				sum += val.get();
			}
			result.set(sum);
			context.write(key, result);
		}
	}



	public static void main(String[] args) throws Exception {
		Configuration conf = new Configuration();

		// Parsing options
		String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
		
		Options options = new Options();
		options.addOption("t", "table", true, "table name");
		options.addOption("w", "where", true, "where clause");
		options.addOption("g", "groupBy", true, "groupBy clause");
		options.addOption("r", "ratio", true, "sampling ratio");
		options.addOption("e", "error", true, "sampling error");
		options.addOption("c", "confidence", true, "confidence level");
		options.addOption("i", "input",    true,  "Input file");
		options.addOption("o", "output",   true,  "Output file");
		options.addOption("f", "filter", true, "filter keyword");
		options.addOption("s", "size", true, "sampling size");

		try {
			CommandLine cmdline = new GnuParser().parse(options, otherArgs);
			String input  = cmdline.getOptionValue("i");
			String output = cmdline.getOptionValue("o");
			int numReducer = 1;
			boolean isError = false;
			if (input == null || output == null) {
				throw new org.apache.commons.cli.ParseException("No input/output option");
			}
			if(cmdline.hasOption("t")) {
				conf.set("map.input.table.name", cmdline.getOptionValue("t"));
			}
			if(cmdline.hasOption("w")) {
				conf.set("map.input.where.clause", cmdline.getOptionValue("w"));
			}
			if(cmdline.hasOption("g")) {
				conf.set("map.input.groupby.clause", cmdline.getOptionValue("g"));
			}
			if(cmdline.hasOption("r")){
				conf.setBoolean("map.input.sampling.ratio", true);
				conf.setBoolean("map.input.sampling.error", false);
				conf.set("map.input.sampling.ratio.value", cmdline.getOptionValue("r"));
			}
			if(cmdline.hasOption("s")){
				conf.setLong("map.input.sample.size", Long.parseLong(cmdline.getOptionValue("s")));
				conf.setBoolean("map.input.sampling.error", false);
			}
			if(cmdline.hasOption("e")){
				isError = true;
				conf.set("mapred.job.error",cmdline.getOptionValue("e"));
				conf.set("mapred.job.confidence",cmdline.getOptionValue("c"));
				conf.setBoolean("map.input.sampling.error", true);
			}
			if(cmdline.hasOption("f")){
				conf.set("map.input.filter", cmdline.getOptionValue("f"));
			}

			//cmdline.getOptionValue
			if(isError){
				Configuration pilotConf = new Configuration(conf);
				pilotConf.setLong("map.input.sample.size", 10000);
				pilotConf.setBoolean("map.input.sample.pilot", true);
				Job job = new Job(pilotConf, "pilot");
				job.setJarByClass(GitHubEvent.class);
				//job.setNumReduceTasks(numReducer);
				job.setMapperClass(GitHubEventMapper.class);
				job.setReducerClass(GitHubEventReducer.class);

				job.setMapOutputKeyClass(Text.class);
				job.setMapOutputValueClass(LongWritable.class);
				job.setOutputKeyClass(Text.class);
				job.setOutputValueClass(DoubleWritable.class);

				job.setPartitionerClass(ApproximatePartitioner.class);

				job.setInputFormatClass(MySampleTextInputFormat.class);

				FileInputFormat.setInputPaths(job,   new Path(input));
				FileOutputFormat.setOutputPath(job, new Path(output+"/pilot"));
				job.waitForCompletion(true);
				//estimate new size according to pilot and error pilotConfidence
				CounterGroup cg = job.getCounters().getGroup("sampleSize");
				Iterator<Counter> iterator = cg.iterator();
				while(iterator.hasNext()){
					Counter ct = iterator.next();
					pilotConf.setLong("map.input.sample.size." + ct.getName(), ct.getValue());
				}
			}

			Job job = new Job(conf, "total of GitHubEvent");
			job.setJarByClass(GitHubEvent.class);
			//job.setNumReduceTasks(numReducer);
			job.setMapperClass(GitHubEventMapper.class);
			job.setReducerClass(GitHubEventReducer.class);

			job.setMapOutputKeyClass(Text.class);
			job.setMapOutputValueClass(DoubleWritable.class);
			job.setOutputKeyClass(Text.class);
			job.setOutputValueClass(DoubleWritable.class);

			job.setPartitionerClass(ApproximatePartitioner.class);

			job.setInputFormatClass(MySampleTextInputFormat.class);

			FileInputFormat.setInputPaths(job,   new Path(input));
			FileOutputFormat.setOutputPath(job, new Path(output));
			job.waitForCompletion(true);


		} catch (org.apache.commons.cli.ParseException exp){
			System.err.println("Error parsing command line: " + exp.getMessage());
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp(GitHubEvent.class.toString(), options);
			ToolRunner.printGenericCommandUsage(System.out);
			System.exit(2);
		}
	}

}