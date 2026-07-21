INSERT INTO product (id, name, price, stock_quantity) VALUES
    (1, '한정판 스니커즈', 129000, 3),
    (2, '무선 이어폰', 89000, 100)
ON CONFLICT (id) DO NOTHING;
