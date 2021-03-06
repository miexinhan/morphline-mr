package com.github.minyk.morphlinesmr;

import com.github.minyk.morphlinesmr.counter.MorphlinesMRCounters;
import com.github.minyk.morphlinesmr.job.MorphlinesJob;
import com.github.minyk.morphlinesmr.mapper.IgnoreKeyOutputFormat;
import com.github.minyk.morphlinesmr.mapper.MorphlinesMapper;
import com.github.minyk.morphlinesmr.partitioner.ExceptionPartitioner;
import com.github.minyk.morphlinesmr.reducer.IdentityReducer;
import com.google.common.base.Preconditions;
import info.ganglia.gmetric4j.gmetric.GMetric;
import org.apache.commons.cli.*;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class MorphlinesMRDriver extends Configured implements Tool {

    private static final Logger LOGGER = LoggerFactory.getLogger(MorphlinesMRDriver.class);
    private static final String RESULT_FILE_PREFIX = "part-r-";
    // Make Job obj.
    private MorphlinesJob job;

    private Options buildOption() {
        Options opts = new Options();
        Option mfile = new Option("f", "morphline-file", true, "target morphline file. Required.");
        mfile.setRequired(false);
        opts.addOption(mfile);

        Option mid = new Option("m", "morphlie-id", true, "target morphline id in the file. Required.");
        mid.setRequired(false);
        opts.addOption(mid);

        Option input = new Option("i", "input", true, "input location. Required.");
        input.setRequired(false);
        opts.addOption(input);

        Option output = new Option("o", "output", true, "output location. Required.");
        output.setRequired(false);
        opts.addOption(output);

        Option exception = new Option("e", "exception", true, "exception location");
        exception.setRequired(false);
        opts.addOption(exception);

        Option reduce = new Option("r", "use-reducer", false, "Use map-reduce process.");
        reduce.setRequired(false);
        opts.addOption(reduce);

        Option reducers = new Option("n", "total-reducers",true, "Total number of reducers. Default is 10 reducers");
        reducers.setRequired(false);
        opts.addOption(reducers);

        Option exceptions = new Option("x", "exception-reducers", true, "Total number of reducers for exception cases. Default is 2 reducers");
        exceptions.setRequired(false);
        opts.addOption(exceptions);

        Option jobname = new Option("j", "job-name", true, "Name of the job.");
        jobname.setRequired(false);
        opts.addOption(jobname);

        Option local = new Option("l", "local-mode", false, "Use local mode.");
        local.setRequired(false);
        opts.addOption(local);

        Option counters = new Option("c", "save-counters", true, "Local path to save counters.");
        counters.setRequired(false);
        opts.addOption(counters);

        Option ganglia = new Option("g", "ganglia", true, "ganglia gmeta server.");
        ganglia.setRequired(false);
        opts.addOption(ganglia);

        return opts;
    }

    @Override
    public int run(String[] args) throws Exception {

        CommandLineParser parser = new BasicParser();
        CommandLine cmd = parser.parse(buildOption(), args, true);

        Configuration config = this.getConf();

        // Load conf from command line.
        if (cmd.hasOption('f')) {
            config.set(MorphlinesMRConfig.MORPHLINE_FILE, cmd.getOptionValue('f'));
        }

        if (cmd.hasOption('m')) {
            config.set(MorphlinesMRConfig.MORPHLINE_ID, cmd.getOptionValue('m'));
        }

        if (cmd.hasOption('i')) {
            config.set(MorphlinesMRConfig.INPUT_PATH, cmd.getOptionValue('i'));
        }

        if (cmd.hasOption('o')) {
            config.set(MorphlinesMRConfig.OUTPUT_PATH, cmd.getOptionValue('o'));
        }

        if (cmd.hasOption('e')) {
            config.set(MorphlinesMRConfig.EXCEPTION_PATH, cmd.getOptionValue('e'));
        } else {
            config.set(MorphlinesMRConfig.EXCEPTION_PATH, MorphlinesMRConfig.EXCEPTION_PATH_DEFAULT);
        }

        if (cmd.hasOption('l')) {
            config.set(MorphlinesMRConfig.MORPHLINESMR_MODE, MorphlinesMRConfig.MORPHLIESMR_MODE_LOCAL);
        } else {
            config.set(MorphlinesMRConfig.MORPHLINESMR_MODE, MorphlinesMRConfig.MORPHLIESMR_MODE_MR);
        }

        if (cmd.hasOption('j')) {
            config.set(MorphlinesMRConfig.JOB_NAME, cmd.getOptionValue('j'));
        }


        if (cmd.hasOption('g')) {
            config.set(MorphlinesMRConfig.METRICS_GANGLAI_SINK, cmd.getOptionValue('g'));
        }

        if (cmd.hasOption('r')) {
            config.set(MorphlinesMRConfig.MORPHLINESMR_REDUCERS, String.valueOf(MorphlinesMRConfig.MORPHLINESMR_REDUCERS));
            config.set(MorphlinesMRConfig.MORPHLINESMR_REDUCERS_EXCEPTION, String.valueOf(MorphlinesMRConfig.MORPHLINESMR_REDUCERS_EXCEPTION));
        }

        if (cmd.hasOption('n')) {
            config.set(MorphlinesMRConfig.MORPHLINESMR_REDUCERS, cmd.getOptionValue('n'));
        }

        if (cmd.hasOption('x')) {
            config.set(MorphlinesMRConfig.MORPHLINESMR_REDUCERS_EXCEPTION, cmd.getOptionValue('x'));
        }

        if (cmd.hasOption('c')) {
            config.set(MorphlinesMRConfig.COUNTER_PATH, cmd.getOptionValue('c'));
        }

        // Do the Job.
        int result = dojob(config);

        if(result == 0) {
            if(cmd.hasOption('c')) {
                saveJobLogs(job);
            }
        }

        return result;
    }

    public MorphlinesJob run(Configuration conf) throws Exception {

        // Do the Job.
        dojob(conf);

        return job;
    }

    private int dojob(Configuration config) throws Exception {
        // Validate conf before start
        validateConf(config);

        if(!config.get(MorphlinesMRConfig.METRICS_GANGLAI_SINK,"").isEmpty()) {
            String ganglia_server = config.get(MorphlinesMRConfig.METRICS_GANGLAI_SINK);
            LOGGER.info("Use ganglia: " + ganglia_server);
            if(ganglia_server.contains(":")) {
                String[] server = ganglia_server.split("\\:");
                job = MorphlinesJob.getInstance(config, config.get(MorphlinesMRConfig.JOB_NAME), server[0], Integer.parseInt(server[1]), GMetric.UDPAddressingMode.getModeForAddress(server[0]) );
            } else {
                job = MorphlinesJob.getInstance(config, config.get(MorphlinesMRConfig.JOB_NAME), ganglia_server, 8649, GMetric.UDPAddressingMode.getModeForAddress(ganglia_server));
            }
        } else {
            job = MorphlinesJob.getInstance(this.getConf(), config.get(MorphlinesMRConfig.JOB_NAME));
        }

        job.setJarByClass(MorphlinesMRDriver.class);
        job.setMapperClass(MorphlinesMapper.class);

        Configuration conf = job.getConfiguration();

        // Prepare values
        int normalReducers = conf.getInt(MorphlinesMRConfig.MORPHLINESMR_REDUCERS, MorphlinesMRConfig.DEFAULT_MORPHLINESMR_REDUCERS);
        int exceptionReducers = conf.getInt(MorphlinesMRConfig.MORPHLINESMR_REDUCERS_EXCEPTION, MorphlinesMRConfig.DEFAULT_MORPHLINESMR_REDUCERS_EXCEPTION);
        int totalReducers = normalReducers + exceptionReducers;

        if (conf.get(MorphlinesMRConfig.MORPHLINESMR_MODE).equals(MorphlinesMRConfig.MORPHLIESMR_MODE_LOCAL)) {
            LOGGER.info("Use local mode.");
            conf.set(MRConfig.FRAMEWORK_NAME,MRConfig.LOCAL_FRAMEWORK_NAME);
            conf.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, CommonConfigurationKeysPublic.FS_DEFAULT_NAME_DEFAULT);

        } else {
            Path morphlinefile = new Path(conf.get(MorphlinesMRConfig.MORPHLINE_FILE));
            LOGGER.info("Use morphlines conf: " + morphlinefile.toString());
            job.addCacheFile(morphlinefile.toUri());
        }

        if( normalReducers != 0 || exceptionReducers != 0) {
            LOGGER.info("Use reducers: true");
            LOGGER.info("Number of total reducers: " + (totalReducers));
            LOGGER.info("Number of exception reducers: " + exceptionReducers);

            job.setNumReduceTasks((totalReducers));
            job.getConfiguration().setInt(ExceptionPartitioner.EXCEPRION_REDUCERS, exceptionReducers);
            job.setReducerClass(IdentityReducer.class);
            job.setPartitionerClass(ExceptionPartitioner.class);
            job.setMapOutputKeyClass(Text.class);
            job.setMapOutputValueClass(Text.class);
        } else {
            LOGGER.info("Use reducers: false");
            job.setNumReduceTasks(0);
        }

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        job.setOutputFormatClass(IgnoreKeyOutputFormat.class);

        String input_path = conf.get(MorphlinesMRConfig.INPUT_PATH);
        String output_path = conf.get(MorphlinesMRConfig.OUTPUT_PATH);

        Path outputPath = new Path(output_path);
        FileSystem fs = FileSystem.get(conf);
        try {
            if(fs.exists(outputPath)) {
                fs.delete(outputPath, true);
                LOGGER.info("Existing output path deleted: " + outputPath.toUri().getPath());
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            throw e;
        } finally {
            fs.close();
        }

        FileInputFormat.addInputPath(job, new Path(input_path));
        IgnoreKeyOutputFormat.setOutputPath(job, outputPath);

        int result = job.waitForCompletion(true) ? 0 : 1;

        if(result == 0) {
            if(exceptionReducers > 0) {
                moveExeptions(totalReducers, normalReducers);
            }
        }

        return result;
    }

    public static void main(String[] args) {
        try {
            ToolRunner.run(new MorphlinesMRDriver(), args );
        } catch (MissingOptionException e) {
            e.getMessage();
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveJobLogs(Job job) throws Exception {

        String logPath = job.getConfiguration().get(MorphlinesMRConfig.COUNTER_PATH);

        File file = new File(logPath);

        if (!file.exists()) {
            file.createNewFile();
        }

        FileWriter fw = new FileWriter(file.getAbsoluteFile(),true);
        BufferedWriter bw = new BufferedWriter(fw);
        bw.write("------------------------------------------------\n");
        bw.write("Job ends at ");
        bw.write(DateTime.now().toString() + "\n");
        //bw.write(content);

        LOGGER.info("Job Name: " + job.getJobName());
        bw.write("Job Name: " + job.getJobName() + "\n");
        // Job ID cannot obtain from job object. See https://issues.apache.org/jira/browse/MAPREDUCE-118
        //bw.write("Job Id:" + job.getJobID().toString());
        bw.write("Input Path: " + job.getConfiguration().get(MorphlinesMRConfig.INPUT_PATH) + "\n");
        bw.write("Output Path: " + job.getConfiguration().get(MorphlinesMRConfig.OUTPUT_PATH) + "\n");
        if(!job.getConfiguration().get(MorphlinesMRConfig.EXCEPTION_PATH).equals(MorphlinesMRConfig.EXCEPTION_PATH_DEFAULT)) {
            bw.write("Exception Path: " + job.getConfiguration().get(MorphlinesMRConfig.EXCEPTION_PATH) + "\n");
        }

        if(job.isSuccessful()) {
            LOGGER.info("Job Status: Successful.");
            bw.write("Job Status: Successful.\n");
            CounterGroup counterGroup = job.getCounters().getGroup(MorphlinesMRCounters.COUNTERGROUP);
            String groupname = counterGroup.getDisplayName();
            for(Counter c : counterGroup) {
                LOGGER.info(groupname + "." +c.getDisplayName() + "=" + c.getValue());
                bw.write(groupname + "." + c.getDisplayName() + " = " + c.getValue() + "\n");
            }
        } else {
            LOGGER.info("Job Status: failed.");
            bw.write("Job Status: failed." + "\n");
        }
        bw.close();
    }

    private void moveExeptions(int totalReducers, int normalReducers) throws IOException {
        Configuration conf = job.getConfiguration();
        FileSystem fs = FileSystem.get(conf);

        Path outputPath = new Path(conf.get(MorphlinesMRConfig.OUTPUT_PATH));
        Path exceptionPath = new Path(conf.get(MorphlinesMRConfig.EXCEPTION_PATH));
        try {
            if(fs.exists(exceptionPath)) {
                fs.delete(exceptionPath, true);
                LOGGER.info("Existing exception path deleted: "+ exceptionPath.toUri().getPath());
            }
            fs.mkdirs(exceptionPath);
            for(int i = totalReducers - 1; i > normalReducers - 1; i-- ) {
                String exceptionOutput = outputPath.toString() + "/" + RESULT_FILE_PREFIX + String.format("%05d", i);
                String exceptionFile = exceptionPath.toString() + "/" + RESULT_FILE_PREFIX + String.format("%05d", i);
                fs.rename(new Path(exceptionOutput), new Path(exceptionFile));
            }

        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        } finally {
            fs.close();
        }
    }

    private void validateConf(Configuration conf) throws Exception  {
        Preconditions.checkNotNull(conf.get(MorphlinesMRConfig.MORPHLINE_FILE), "Error: " + MorphlinesMRConfig.MORPHLINE_FILE + " should be set by hadoop common option or -f option.");
        Preconditions.checkNotNull(conf.get(MorphlinesMRConfig.MORPHLINE_ID), "Error: " + MorphlinesMRConfig.MORPHLINE_ID + " should be set by hadoop common option or -m option.");
        Preconditions.checkNotNull(conf.get(MorphlinesMRConfig.INPUT_PATH), "Error: " + MorphlinesMRConfig.INPUT_PATH + " should be set by hadoop common option or -i option.");
        Preconditions.checkNotNull(conf.get(MorphlinesMRConfig.OUTPUT_PATH), "Error: " + MorphlinesMRConfig.OUTPUT_PATH + " should be set by hadoop common option or -o option.");
    }

}
