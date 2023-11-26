import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import javax.sound.sampled.*;

class Dialog {

    public static final int SAMPLE_RATE = 4000;

    public static final double RMS_WINDOW = 0.25;
    public static final double STARTUP_WINDOW = 0.45;
    public static final double SMOOTH_WINDOW = 1.0;
    public static final double PAUSE_DURATION = 2.0;

    private double DERIVATIVE_THRESH = 0.00025;
    private double rmsThresh;

    private int rmsSamples;
    private int smoothSamples;
    private int pauseSamples;
    private int startupSamples;

    private int pauseStart;

    private double[] samples, rmsData, log, smoothed, derivative;

    private State status;

    private AudioFormat audioFormat;
    private TargetDataLine mic;
    private SotaController roboController;
    ArrayList<CsvWriter> writers;

    public Dialog() {
        this.rmsSamples = (int)(SAMPLE_RATE*RMS_WINDOW); 
        this.smoothSamples = (int)(SAMPLE_RATE*SMOOTH_WINDOW);
        this.pauseSamples = (int)(SAMPLE_RATE*PAUSE_DURATION);
        this.startupSamples = (int)(SAMPLE_RATE*STARTUP_WINDOW);

        this.status = State.STARTUP;
        this.roboController = new SotaController(this.status);
    }

    public void init () throws LineUnavailableException, IOException {
        this.audioFormat = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, this.audioFormat);

        this.mic = (TargetDataLine)AudioSystem.getLine(lineInfo);

        //max amount of data needed in buffers
        int buffsize = mic.getBufferSize() + this.smoothSamples;

        this.samples = new double[buffsize];
        this.rmsData = new double[buffsize];
        this.log = new double[buffsize];
        this.smoothed = new double[buffsize];
        this.derivative = new double[buffsize];

        this.writers = new ArrayList<CsvWriter>();
        this.writers.add(new CsvWriter("./samples.csv", samples));
        this.writers.add(new CsvWriter("./rms.csv", rmsData));
        this.writers.add(new CsvWriter("./log.csv", log));
        this.writers.add(new CsvWriter("./smoothed.csv", smoothed));
        this.writers.add(new CsvWriter("./derivative.csv", derivative));
    }

    public void run() {

        double[] gaussKernel = gaussianKernel(1, this.smoothSamples);
        double[] derivativeKernel = new double[]{-1, 1};

        int samplesRead = 0;
        int offset = 0;

        try {

            init();

            this.mic.open(this.audioFormat);
            this.mic.start();

            byte[] bytes = new byte[mic.getBufferSize()];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while(System.in.available() == 0) { //loop until keypress

                samplesRead += mic.read(bytes, 0, mic.available()) / 2;

                //bytes to samples
                buffer.position(0);
                for(int i = offset; i < samplesRead; i++) {
                    samples[i % samples.length] = (double)buffer.getShort();
                }

                //rms
                for(int i = offset; i < samplesRead; i++) {
                     rmsData[i % rmsData.length] = rms(i-this.rmsSamples+1, this.rmsSamples, samples); 
                }

                //log;
                for(int i = offset; i < samplesRead; i++) {
                    log[i % log.length] = Math.log10(rmsData[i % rmsData.length]);
                }

                //gaussian smooth
                for(int i = offset; i < samplesRead; i++) {
                    smoothed[i % smoothed.length] = convolveStep(i-this.smoothSamples+1, gaussKernel, log); 
                }

                //derivative
                for(int i = offset; i < samplesRead; i++) {
                    derivative[i % derivative.length] = convolveStep(i-1, derivativeKernel, smoothed);
                }

                if(samplesRead >= smoothSamples+startupSamples) {
                    writeToCSVs(offset, samplesRead);
                    updateStatus(offset, samplesRead);
                }

            
                offset = samplesRead;
            }
  
            teardown();
            System.out.println(this.rmsThresh);
  

        } catch (Exception e) {
            System.out.println("something went wrong: " + e.toString());
        }
    }

    public void teardown() throws IOException {
        this.mic.stop();
        this.mic.close();
        for(CsvWriter writer : this.writers) {
            writer.close();
        }
    }

    public double rms(int offset, int length, double[] data) {
        int index = (offset + data.length) % data.length;

        double sum = 0;
        for(int i = 0; i < length; i++) {
            sum += data[index]*data[index]; 
            index = (index == data.length-1) ? 0 : index+1; //conditional instead of mod for performance
        }

        return Math.sqrt(sum / length); 
    }

    public double convolveStep(int offset, double[] kernel, double[] data) {
        int index = (offset + data.length) % data.length;
        
        double sum = 0;
        for(int i = 0; i < kernel.length; i++) {
            sum += kernel[i]*data[index];
            index = (index == data.length-1) ? 0 : index+1;
        }

        return sum;
    }

    public double[] gaussianKernel(double sigma, int size) {
        double[] filter = new double[size];

        int range = (int)Math.ceil(3*sigma); //sample between [-3*sigma, 3*sigma]
        double step = (range*2)/((double)size-1); //interval for samples
       
        double sum = 0; //sum for normalization
        double pos = -range; //distance from mean

        //compute values 
        for(int i = 0; i < size; i++) {
            filter[i] = (1 / (sigma*Math.sqrt(2*Math.PI))) * Math.exp(-(pos*pos)/(2*sigma*sigma));
            sum += filter[i];
            pos+=step;
        }

        //normalize
        for(int i = 0; i < size; i++) {
            filter[i] /= sum;
        }

        return filter;
    }

    public void updateStatus(int begin, int end) {

        for(int i = begin; i < end; i++) {
            if(this.status == State.STARTUP) {
                this.rmsThresh = max(begin-startupSamples, startupSamples, this.smoothed)*1.5;
                System.out.println(rmsThresh);
  
                this.status = State.STOPPED;
                roboController.updateStatus(this.status);

            } else if(this.status != State.TALKING && derivative[i % derivative.length] > DERIVATIVE_THRESH) {

                this.status = State.TALKING;
                roboController.updateStatus(this.status);
                System.out.println("TALKING");

            } else if (this.status == State.TALKING && derivative[i % derivative.length] < -DERIVATIVE_THRESH && smoothed[i % smoothed.length] < rmsThresh) {

                this.status = State.PAUSED;
                pauseStart = i;
                roboController.updateStatus(this.status);
                System.out.println("PAUSE");

            } else if (this.status == State.PAUSED && i - pauseStart > pauseSamples) {  

                this.status = State.STOPPED;
                roboController.updateStatus(this.status);
                System.out.println("STOPPED");
            }
        }
    }

    public double max(int offset, int length, double[] data) {
        double max = -Double.MIN_VALUE;
        for(int i = offset; i < offset+length; i++) {
            if(data[i] > max) {
                max = data[i];
            }
        }
        return max;
    }

    public void writeToCSVs(int begin, int end) throws IOException {
        for(CsvWriter w : this.writers) {
            w.write(begin, end);
        }
    }

    public static void main(String[] args) {
        Dialog d = new Dialog();
        d.run();
    }
}