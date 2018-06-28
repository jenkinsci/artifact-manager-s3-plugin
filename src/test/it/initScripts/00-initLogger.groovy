import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.text.MessageFormat
import java.util.logging.SimpleFormatter
import java.util.logging.LogManager
import jenkins.model.Jenkins
import java.util.logging.LogRecord

// Log into the console
def WebAppMainLogger = LogManager.getLogManager().getLogger("hudson.WebAppMain")
WebAppMainLogger.addHandler (new ConsoleHandler())

// Log into a file
def RunLogger = LogManager.getLogManager().getLogger("hudson.model.Run")
FileHandler handler = new FileHandler("/var/jenkins_home/jenkins.log", 1024 * 1024, 10, true);
def formatter= (new SimpleFormatter());

handler.setFormatter(formatter);
RunLogger.addHandler(handler);