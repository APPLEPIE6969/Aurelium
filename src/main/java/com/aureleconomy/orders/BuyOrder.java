package com.aureleconomy.orders;

import org.bukkit.Material;
import java.util.UUID;

public class BuyOrder {
    private final int id;
    private final UUID buyerUuid;
    private final Material material;
    private final int amountRequested;
    private int amountFilled;
    private final double pricePerPiece;
    private String status;

    public BuyOrder(int id, UUID buyerUuid, Material material, int amountRequested, int amountFilled,
            double pricePerPiece, String status) {
        this.id = id;
        this.buyerUuid = buyerUuid;
        this.material = material;
        this.amountRequested = amountRequested;
        this.amountFilled = amountFilled;
        this.pricePerPiece = pricePerPiece;
        this.status = status;
    }

    public int getId() {
        return id;
    }

    public UUID getBuyerUuid() {
        return buyerUuid;
    }

    public Material getMaterial() {
        return material;
    }

    public int getAmountRequested() {
        return amountRequested;
    }

    public int getAmountFilled() {
        return amountFilled;
    }

    public double getPricePerPiece() {
        return pricePerPiece;
    }

    public String getStatus() {
        return status;
    }

    public int getAmountRemaining() {
        return amountRequested - amountFilled;
    }

    public void setAmountFilled(int amountFilled) {
        this.amountFilled = amountFilled;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
