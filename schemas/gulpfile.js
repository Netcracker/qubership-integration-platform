import { series, src, dest } from "gulp";
import clean_ from "gulp-clean";

export function clean() {
    return src("assets", { allowEmpty: true }).pipe(clean_());
}

export function assembly() {
    return src("src/main/resources/**/*.yaml")
        .pipe(dest("assets"));
}

export default series(clean, assembly);
