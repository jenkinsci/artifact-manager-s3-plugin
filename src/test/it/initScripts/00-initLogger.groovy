import java.util.logging.FileHandler
import java.util.logging.SimpleFormatter
import java.util.logging.Logger
import java.util.logging.Level

Logger glogger= Logger.getLogger("global")
glogger.setLevel(Level.INFO)

Logger defaultlogger= Logger.getLogger("")
defaultlogger.setLevel(Level.INFO)

Logger hudsonlogger= Logger.getLogger("hudson")
hudsonlogger.setLevel(Level.INFO)

Logger jenkinslogger= Logger.getLogger("jenkins")
jenkinslogger.setLevel(Level.INFO)

Logger winstonelogger= Logger.getLogger("winstone")
winstonelogger.setLevel(Level.INFO)

Logger sshlogger= Logger.getLogger("org.apache.sshd")
sshlogger.setLevel(Level.INFO)

FileHandler handler = new FileHandler("/var/jenkins_home/jenkins-%u.log", 1024 * 1024, 10, true)
def formatter= (new SimpleFormatter())

handler.setFormatter(formatter)
glogger.addHandler(handler)
jenkinslogger.addHandler(handler)
hudsonlogger.addHandler(handler)
defaultlogger.addHandler(handler)
winstonelogger.addHandler(handler)
sshlogger.addHandler(handler)

glogger.info("Loggers configured")