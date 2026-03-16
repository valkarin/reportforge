package com.buraktok.reportforge.ui;

import javafx.scene.Scene;
import javafx.stage.Stage;

public interface WindowContext {
    Stage getPrimaryStage();

    Scene getScene();

    ThemeMode getThemeMode();
}
