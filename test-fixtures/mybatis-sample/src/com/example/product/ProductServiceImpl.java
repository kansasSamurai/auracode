package com.example.product;

public class ProductServiceImpl implements ProductService {

    @Override
    public String findById(Long id) {
        return "Product-" + id;
    }

    @Override
    public String findByName(String name) {
        return name;
    }
}
