import { isVsCode } from "../../api/rest/vscodeExtensionApi.ts";
import { IntegrationSystemType } from "../../api/apiTypes.ts";
import { useNavigate } from "react-router-dom";
import { capitalize } from "../../misc/format-utils.ts";

type ServiceNameBreadcrumbItemProps = {
  type?: IntegrationSystemType;
  id?: string;
  name?: string;
};

type ServiceTypeBreadcrumbItemProps = {
  type?: IntegrationSystemType;
};

export const ServiceNameBreadcrumbItem: React.FC<
  ServiceNameBreadcrumbItemProps
> = (props: ServiceNameBreadcrumbItemProps) => {
  const navigate = useNavigate();

  const url =
    props.type === IntegrationSystemType.CONTEXT
      ? `/services/context/${props.id}/parameters`
      : `/services/systems/${props.id}/specificationGroups`;

  return (
      <a
        onClick={(e) => {
          e.preventDefault();
          void navigate(url);
        }}
        href={url}
      >
        {props.name || props.id || "..."}
      </a>
  );
};

export const ServiceTypeBreadcrumbItem: React.FC<
  ServiceTypeBreadcrumbItemProps
> = (props: ServiceTypeBreadcrumbItemProps) => {
  const navigate = useNavigate();

  const getTypeLabel = (type?: IntegrationSystemType) => {
    return type
      ? capitalize(IntegrationSystemType[type]) + " Services"
      : "Services";
  };

  const getTabHash = (type?: IntegrationSystemType) => {
    return IntegrationSystemType[
      type ?? IntegrationSystemType.IMPLEMENTED
    ].toLowerCase();
  };

  const url = `/services#${getTabHash(props.type)}`;
  const label = getTypeLabel(props.type);

  return (
    <>
      {isVsCode ? (
        <span>{label}</span>
      ) : (
        <a
          onClick={(e) => {
            e.preventDefault();
            void navigate(url);
          }}
          href={url}
        >
          {label}
        </a>
      )}
    </>
  );
};
