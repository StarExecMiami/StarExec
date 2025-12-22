
				SELECT 	benchmarks.id AS id,
						benchmarks.name AS name,
						benchmarks.description AS description,
						deleted,
						recycled,
						user_id,
						processors.name							AS benchTypeName,
						processors.description					AS benchTypeDescription
				
				FROM 	benchmarks
					JOIN	bench_assoc ON benchmarks.id = bench_assoc.bench_id	AND bench_assoc.space_id= :spaceId	
					LEFT JOIN	processors ON benchmarks.bench_type=processors.id


				
				-- Query Filtering
				WHERE 	(benchmarks.name 									LIKE	CONCAT('%', :query, '%')
				OR		(processors.name	LIKE 	CONCAT('%', :query, '%') 
				OR (processors.name is null AND 'none' LIKE CONCAT('%', :query, '%'))))
