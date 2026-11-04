package com.example.ledger.supplier;

import com.example.ledger.common.ApiResponse;
import com.example.ledger.supplier.dto.SupplierUpsertRequest;
import com.example.ledger.supplier.dto.SupplierView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    public ApiResponse<List<SupplierView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(supplierService.list(keyword, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierView> get(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.get(id));
    }

    @PostMapping
    public ApiResponse<SupplierView> create(@Valid @RequestBody SupplierUpsertRequest request) {
        return ApiResponse.ok(supplierService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierView> update(@PathVariable Long id,
                                            @Valid @RequestBody SupplierUpsertRequest request) {
        return ApiResponse.ok(supplierService.update(id, request));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        supplierService.disable(id);
        return ApiResponse.ok(null);
    }
}