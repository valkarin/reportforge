module com.buraktok.reportforge {
    requires javafx.controls;
    requires java.prefs;
    requires java.sql;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires org.xerial.sqlitejdbc;

    opens com.buraktok.reportforge.persistence to com.fasterxml.jackson.databind;
    exports com.buraktok.reportforge;
}
