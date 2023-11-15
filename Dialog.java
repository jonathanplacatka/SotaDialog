import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import javax.sound.sampled.*;

class Dialog {

    public static final int SAMPLE_RATE = 22050;
    public static final int CHUNK_SIZE = 1024;

    public static final double RMS_WINDOW = 0.25;
    public static final double SMOOTH_WINDOW = 1.0;

    private int rmsSamples;
    private int smoothSamples;

    private double[] samples;
    private double[] rmsData;
    private double[] log;
    private double[] smoothed;
    private double[] derivative;

    private AudioFormat format;
    private DataLine.Info lineInfo;

    ArrayList<CsvWriter> writers;

    public Dialog() {
        this.format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        this.lineInfo = new DataLine.Info(TargetDataLine.class, format);

        this.rmsSamples = (int)(SAMPLE_RATE*RMS_WINDOW); 
        this.smoothSamples = (int)(SAMPLE_RATE*SMOOTH_WINDOW);

        //max amount of data needed in buffers
        int buffsize = (int)Math.max(this.rmsSamples, this.smoothSamples) + CHUNK_SIZE; 

        this.samples = new double[buffsize];
        this.rmsData = new double[buffsize];
        this.log = new double[buffsize];
        this.smoothed = new double[buffsize];
        this.derivative = new double[buffsize];
    }

    public void run() {

        byte[] bytes = new byte[CHUNK_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        double[] gaussKernel = gaussianKernel(1, this.smoothSamples);
        double[] derivativeKernel = new double[]{-1, 1};

        try {
            
            initWriters();

            TargetDataLine mic = (TargetDataLine)AudioSystem.getLine(this.lineInfo);
            mic.open(format);
            mic.start();

            int samplesRead = 0;
            int offset = 0;
 
            while(System.in.available() == 0) { //loop until keypress
                
                //read from mic
                samplesRead += mic.read(bytes, 0, CHUNK_SIZE) / 2;
 
                //bytes to samples
                buffer.position(0);
                for(int i = offset; i < samplesRead; i++) {
                    samples[i % samples.length] = (double)buffer.getShort();
                }

                //rms
                for(int i = offset; i < samplesRead; i++) {
                    rmsData[i % rmsData.length] = rms(i-this.rmsSamples+1, i+1, samples);    
                }

                //log;
                for(int i = offset; i < samplesRead; i++) {
                    log[i % log.length] = log10rms(rmsData[i % rmsData.length]);
                }

                //gaussian smooth
                for(int i = offset; i < samplesRead; i++) {
                    smoothed[i % smoothed.length] = convolveStep(i-this.smoothSamples+1, gaussKernel, log); 
                }

                //derivative
                for(int i = offset; i < samplesRead; i++) {
                    derivative[i % derivative.length] = convolveStep(i-1, derivativeKernel, smoothed);
                }

                writeToCSVs(offset, samplesRead);
                offset = samplesRead;
            }

            mic.close();
            closeWriters();

        } catch (Exception e) {
            System.out.println("something went wrong: " + e.toString());
        }
    }

    //handle 0 values - RMS can sometimes be 0 at startup, but java defines log(0) as -Infinity
    public double log10rms(double val) {
        if(val == 0) {         
            val+=0.1;
        } 
        return Math.log10(val);
    }
    
    public double rms(int begin, int end, double[] data) {
        int index;
        double sum = 0;

        for(int i = begin; i < end; i++) {
            index = (i+data.length) % data.length; //circular index, handles negatives
            sum += data[index]*data[index];
        }

        return Math.sqrt(sum / (end-begin)); 
    }

    public double convolveStep(int offset, double[] kernel, double[] data) {
        int index;
        double sum = 0;

        for(int i = 0; i < kernel.length; i++) {
            index = (i+offset+data.length) % data.length; //circular index, handles negatives
            sum += kernel[i]*data[index];
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

    private void initWriters() throws IOException {
        writers = new ArrayList<CsvWriter>();
        writers.add(new CsvWriter("./samples.csv", samples));
        writers.add(new CsvWriter("./rms.csv", rmsData));
        writers.add(new CsvWriter("./log.csv", log));
        writers.add(new CsvWriter("./smoothed.csv", smoothed));
        writers.add(new CsvWriter("./derivative.csv", derivative));
    }

    private void closeWriters() throws IOException {
        for(CsvWriter writer : this.writers) {
            writer.close();
        }
    }

    public void writeToCSVs(int offset, int len) throws IOException {
        for(CsvWriter w : this.writers) {
            w.write(offset, len);
        }
    }

    public static void main(String[] args) {
        Dialog d = new Dialog();
        d.run();
    }

}