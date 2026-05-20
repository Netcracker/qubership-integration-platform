create or replace function is_parent_folder(
    parent_id varchar(255),
    folder_id varchar(255)
) returns bool as $$
declare
result bool;
begin
with recursive folder_hierarchy as (
    select
        f1.*
    from
        catalog.folders f1
    where f1.id = folder_id
    union all
    select
        f2.*
    from catalog.folders f2
             inner join folder_hierarchy fh
                        on f2.id = fh.parent_folder_id
)
select count(*) > 0 into result from folder_hierarchy f where f.id = parent_id;
return result;
end;
$$ language plpgsql;
