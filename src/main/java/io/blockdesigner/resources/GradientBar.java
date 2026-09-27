package io.blockdesigner.resources;

import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * A thin progress bar whose fill runs red, orange, yellow, green from left to right: the gradient spans the whole bar
 * and the fill uncovers it, so an item that has barely started shows red and a finished one ends in green.
 */
final class GradientBar extends Region {
    private final Region fill = new Region();
    private final Rectangle clip = new Rectangle();
    private double value;

    GradientBar(double height) {
        setMinHeight(height);
        setPrefHeight(height);
        setMaxHeight(height);
        setMinWidth(20);
        setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 3; -fx-border-color: -color-border-muted;"
                + " -fx-border-radius: 3; -fx-border-width: 1;");
        fill.setStyle("-fx-background-color: linear-gradient(to right, #d8413a 0%, #e8872f 33%, #e9cf3f 66%, #5dbe4a 100%);"
                + " -fx-background-radius: 3;");
        fill.setClip(clip);
        fill.setMouseTransparent(true);
        getChildren().add(fill);
    }

    /** How full, 0 to 1. */
    void setValue(double v) {
        value = Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
        requestLayout();
    }

    double value() {
        return value;
    }

    @Override
    protected void layoutChildren() {
        fill.resizeRelocate(0, 0, getWidth(), getHeight());
        clip.setWidth(Math.round(getWidth() * value));
        clip.setHeight(getHeight());
    }
}
