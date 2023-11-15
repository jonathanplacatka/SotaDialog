import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

class CsvWriter {
    private BufferedWriter bw;
    private double[] data;

    public CsvWriter(String filepath, double[] data) throws IOException {
        this.data = data;
        this.bw = Files.newBufferedWriter(Paths.get(filepath));
    }

    public void write(int begin, int end) throws IOException {
        String toWrite;

        for(int i = begin; i < end; i++) {
            toWrite = String.valueOf(this.data[(i % data.length)]);
            bw.write(toWrite+",");
        }
    }

    public void close() throws IOException {
        bw.close();
    }
}