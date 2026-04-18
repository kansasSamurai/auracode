package com.example.product;

/**
 * Unrelated service interface that deliberately also declares findById(Long).
 * Used to prove that hierarchy-aware dispatch does NOT cross-contaminate:
 * findConcreteCalleeFqns("UserService#findById(Long)") must not return
 * ProductServiceImpl#findById(Long) even though the method signature matches.
 */
public interface ProductService {

    String findById(Long id);

    String findByName(String name);
}
