/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.training.listener;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.metric.Metrics;
import ai.djl.training.Trainer;
import ai.djl.training.evaluator.Evaluator;
import ai.djl.training.loss.Loss;
import ai.djl.training.util.ProgressBar;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TrainingListener} that outputs the progress of training each batch and epoch into logs.
 */
public class LoggingTrainingListener implements TrainingListener {

    private static final Logger logger = LoggerFactory.getLogger(LoggingTrainingListener.class);

    private int batchSize;
    private int trainDataSize;
    private int validateDataSize;
    private int trainingProgress;
    private int validateProgress;

    private int numEpochs;

    private ProgressBar trainingProgressBar;
    private ProgressBar validateProgressBar;

    /**
     * Constructs a {@link LoggingTrainingListener}.
     *
     * @param batchSize the size of training batches
     * @param trainDataSize the total number of elements in the training dataset
     * @param validateDataSize the total number of elements in the validation dataset
     */
    public LoggingTrainingListener(int batchSize, int trainDataSize, int validateDataSize) {
        this.batchSize = batchSize;
        this.trainDataSize = trainDataSize;
        this.validateDataSize = validateDataSize;
    }

    /** {@inheritDoc} */
    @Override
    public void onEpoch(Trainer trainer) {
        Metrics metrics = trainer.getMetrics();
        Loss loss = trainer.getLoss();
        logger.info("Epoch " + numEpochs + " finished.");

        logger.info(
                "Train: "
                        + getEvaluatorsStatus(metrics, trainer.getTrainingEvaluators(), "train_"));
        if (metrics.hasMetric("validate_" + loss.getName())) {
            logger.info(
                    "Validate: "
                            + getEvaluatorsStatus(
                                    metrics, trainer.getValidationEvaluators(), "validate_"));
        } else {
            logger.info("validation has not been run.");
        }

        numEpochs++;
        trainingProgress = 0;
        validateProgress = 0;
    }

    /** {@inheritDoc} */
    @Override
    public void onTrainingBatch(Trainer trainer) {
        if (trainingProgressBar == null) {
            trainingProgressBar = new ProgressBar("Training", trainDataSize);
        }
        trainingProgressBar.update(trainingProgress++, getTrainingStatus(trainer));
    }

    private String getTrainingStatus(Trainer trainer) {
        Metrics metrics = trainer.getMetrics();
        StringBuilder sb = new StringBuilder();

        sb.append(getEvaluatorsStatus(metrics, trainer.getTrainingEvaluators(), "train_"));

        if (metrics.hasMetric("train")) {
            float batchTime = metrics.latestMetric("train").getValue().longValue() / 1_000_000_000f;
            sb.append(String.format(", speed: %.2f images/sec", (float) batchSize / batchTime));
        }
        return sb.toString();
    }

    /** {@inheritDoc} */
    @Override
    public void onValidationBatch(Trainer trainer) {
        if (validateProgressBar == null) {
            validateProgressBar = new ProgressBar("Validating", validateDataSize);
        }
        validateProgressBar.update(validateProgress++);
    }

    /** {@inheritDoc} */
    @Override
    public void onTrainingBegin(Trainer trainer) {
        String devicesMsg;
        List<Device> devices = trainer.getDevices();
        if (devices.size() == 1 && Device.Type.CPU.equals(devices.get(0).getDeviceType())) {
            devicesMsg = Device.cpu().toString();
        } else {
            devicesMsg = devices.size() + " GPUs";
        }
        logger.info("Running {} on: {}.", getClass().getSimpleName(), devicesMsg);

        long init = System.nanoTime();
        String engineName = Engine.getInstance().getEngineName();
        String version = Engine.getInstance().getVersion();
        long loaded = System.nanoTime();
        logger.info(
                String.format(
                        "Load %s Engine Version %s in %.3f ms.",
                        engineName, version, (loaded - init) / 1_000_000f));
    }

    /** {@inheritDoc} */
    @Override
    public void onTrainingEnd(Trainer trainer) {
        Metrics metrics = trainer.getMetrics();
        logger.info("Training: {} batches", trainDataSize);
        logger.info("Validation: {} batches", validateDataSize);

        if (metrics.hasMetric("train")) {
            // possible no train metrics if only one iteration is executed
            float p50 = metrics.percentile("train", 50).getValue().longValue() / 1_000_000f;
            float p90 = metrics.percentile("train", 90).getValue().longValue() / 1_000_000f;
            logger.info(String.format("train P50: %.3f ms, P90: %.3f ms", p50, p90));
        }

        float p50 = metrics.percentile("forward", 50).getValue().longValue() / 1_000_000f;
        float p90 = metrics.percentile("forward", 90).getValue().longValue() / 1_000_000f;
        logger.info(String.format("forward P50: %.3f ms, P90: %.3f ms", p50, p90));

        p50 = metrics.percentile("training-metrics", 50).getValue().longValue() / 1_000_000f;
        p90 = metrics.percentile("training-metrics", 90).getValue().longValue() / 1_000_000f;
        logger.info(String.format("training-metrics P50: %.3f ms, P90: %.3f ms", p50, p90));

        p50 = metrics.percentile("backward", 50).getValue().longValue() / 1_000_000f;
        p90 = metrics.percentile("backward", 90).getValue().longValue() / 1_000_000f;
        logger.info(String.format("backward P50: %.3f ms, P90: %.3f ms", p50, p90));

        p50 = metrics.percentile("step", 50).getValue().longValue() / 1_000_000f;
        p90 = metrics.percentile("step", 90).getValue().longValue() / 1_000_000f;
        logger.info(String.format("step P50: %.3f ms, P90: %.3f ms", p50, p90));

        p50 = metrics.percentile("epoch", 50).getValue().longValue() / 1_000_000_000f;
        p90 = metrics.percentile("epoch", 90).getValue().longValue() / 1_000_000_000f;
        logger.info(String.format("epoch P50: %.3f s, P90: %.3f s", p50, p90));
    }

    private String getEvaluatorsStatus(Metrics metrics, List<Evaluator> toOutput, String prefix) {
        List<String> metricOutputs = new ArrayList<>();
        for (Evaluator evaluator : toOutput) {
            float value =
                    metrics.latestMetric(prefix + evaluator.getName()).getValue().floatValue();
            // use .2 precision to avoid new line in progress bar
            metricOutputs.add(String.format("%s: %.2f", evaluator.getName(), value));
        }
        return String.join(", ", metricOutputs);
    }
}
