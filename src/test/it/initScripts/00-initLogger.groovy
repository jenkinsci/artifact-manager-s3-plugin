import java.util.logging.FileHandler
import java.util.logging.SimpleFormatter
import java.util.logging.Logger
import java.util.logging.Level

// Log into a file
Logger glogger= Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
glogger.setLevel(Level.INFO);
FileHandler handler = new FileHandler("/var/jenkins_home/jenkins.log", 1024 * 1024, 10, true);
def formatter= (new SimpleFormatter());

handler.setFormatter(formatter);
glogger.addHandler(handler);